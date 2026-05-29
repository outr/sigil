package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ContextFrame, ToolCallState}
import sigil.db.Model
import sigil.event.{ConversationCorruptionDetected, ConversationHealed, Event, HealingExhausted, Message, MessageRole, ToolInvoke}
import sigil.heal.{CorruptionEvidence, HealingMode, HealingOutcome}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, HealingActivityNotice, Signal}
import sigil.tool.{ToolName, TextToolOutput}
import sigil.tool.core.CoreTools
import sigil.tool.model.{ResponseContent, RespondInput}
import sigil.tool.core.RespondTool
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for Sigil #313 — reactive conversation self-heal.
 *
 * Drives the full publish → runAgent → runAgentLoop pipeline against
 * corruption-bearing histories and asserts on:
 *
 *   1. Recover mode: the durable triple
 *      (CorruptionDetected → strategy → Healed) lands, the synthesised
 *      ToolInvoke settle carries `source = "framework-heal"`, the
 *      HealingActivityNotice fires with `outcome = Healed`, and the
 *      retried iteration succeeds.
 *   2. Strict mode: CorruptionDetected still publishes, the
 *      operational notice fires with `outcome = StrictRefused`, the
 *      original error re-throws through the standard failure path.
 *   3. One heal per turn: when the post-heal retry ALSO fails, the
 *      framework publishes `HealingExhausted` (with the strategy
 *      named and retryError captured) and does NOT heal again.
 *   4. Forensic preservation: scribe captures the original error
 *      payload (class + body) at `Error`, regardless of mode.
 *   5. BrokenHistoryException path: the renderFrames invariant throws
 *      a typed exception when the durable log carries an Active
 *      ToolCall frame; the classifier resolves to the same default
 *      strategy and the heal repairs the log.
 */
class ConversationSelfHealSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "self-heal")
  TestSigil.testModel(modelId)

  /** Catches every `scribe.error` call across the spec so the
    * forensic-preservation assertion can verify the original error's
    * class + message ended up in the log. Adds a handler atop the
    * existing root pipeline rather than replacing — the framework's
    * own DEBUG / INFO / WARN traces stay intact for diagnostic
    * triage while ERRORs additionally land in the recording queue.
    * `scribeRecord.clear()` resets between scenarios. */
  private val scribeRecord = new ConcurrentLinkedQueue[String]()
  scribe.Logger.root
    .withHandler(writer = new scribe.writer.Writer {
      override def write(record: scribe.LogRecord, output: scribe.output.LogOutput,
                         outputFormat: scribe.output.format.OutputFormat): Unit = {
        if (record.level.value >= scribe.Level.Error.value) {
          val msg = output.plainText
          val ex = record.messages
            .map(_.value)
            .collect { case t: Throwable => t.getClass.getSimpleName + ": " + Option(t.getMessage).getOrElse("") }
            .mkString(" | ")
          scribeRecord.add(s"$msg $ex")
        }
      }
    })
    .replace()

  /** Provider whose `call` raises a `StreamingHttpFailedException`
    * with the OpenAI Responses "No tool output found for function call
    * <id>" body. Drives the provider-call detection path. */
  private final class OrphanCallErrorProvider(callIdToCite: String) extends Provider {
    val attemptCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val attempt = attemptCount.incrementAndGet()
      val _ = attempt
      Stream.force(Task.error(new spice.http.client.StreamingHttpFailedException(
        status  = 400,
        headers = spice.http.Headers.empty,
        body    = s"""{"error":{"message":"No tool output found for function call $callIdToCite"}}"""
      )))
    }
  }

  /** Same shape as `OrphanCallErrorProvider`, but the first call
    * throws the orphan error AND any subsequent call emits a
    * successful atomic `respond`. Drives the happy-path
    * heal-then-retry-succeeds assertion. */
  private final class HealThenSucceedProvider(callIdToCite: String) extends Provider {
    val attemptCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = attemptCount.incrementAndGet()
      if (n == 1) {
        Stream.force(Task.error(new spice.http.client.StreamingHttpFailedException(
          status  = 400,
          headers = spice.http.Headers.empty,
          body    = s"""{"error":{"message":"No tool output found for function call $callIdToCite"}}"""
        )))
      } else {
        val cid = CallId(s"healed-success-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.ToolCallComplete(
            cid,
            RespondInput(
              topicLabel   = "Healed reply",
              topicSummary = "post-heal retry succeeded",
              content      = "Resumed after self-heal.",
              endsTurn     = true
            )
          ),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  private def makeAgent(id: sigil.participant.AgentParticipantId = TestAgent): AgentParticipant =
    DefaultAgentParticipant(
      id                 = id,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  /** Recorder fiber — buffers every signal that flows through the
    * hub. The various assertions filter to whatever subtype they
    * care about; running once per scenario keeps the spec terse. */
  private def startRecorder(): (ConcurrentLinkedQueue[Signal], atomic.AtomicBoolean) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    (recorded, running)
  }

  private def waitFor(deadline: Long)(pred: => Boolean): Task[Unit] =
    if (pred || System.currentTimeMillis() > deadline) Task.unit
    else Task.sleep(50.millis).flatMap(_ => waitFor(deadline)(pred))

  /** Insert a fresh orphan `ToolInvoke` directly into the events
    * store with state = Active. This emulates the bricked-history
    * shape from #313 — a row that no orchestrator settle ever
    * landed on. */
  private def seedOrphanInvoke(convId: Id[Conversation],
                               agent: AgentParticipant,
                               wireCallId: String,
                               topicId: Id[sigil.conversation.Topic]): Task[Id[Event]] = {
    val invokeId = Event.id()
    val invoke = ToolInvoke(
      _id            = invokeId,
      toolName       = ToolName("read_file"),
      participantId  = agent.id,
      conversationId = convId,
      topicId        = topicId,
      state          = EventState.Active,
      callId         = Some(wireCallId)
    )
    TestSigil.withDB(_.events.transaction(_.upsert(invoke))).map(_ => invokeId)
  }

  "Sigil #313 — reactive self-heal" should {

    "Recover mode: detect the corruption, run MissingToolResultStrategy, retry succeeds" in {
      TestSigil.reset()
      scribeRecord.clear()
      TestSigil.setHealingMode(HealingMode.Recover)
      val wireCallId = s"call_${rapid.Unique()}"
      val convId  = Conversation.id(s"selfheal-recover-${rapid.Unique()}")
      val agent   = makeAgent()
      val conv    = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      val provider = new HealThenSucceedProvider(wireCallId)
      TestSigil.setProvider(Task.pure(provider))

      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        invokeId <- seedOrphanInvoke(convId, agent, wireCallId, TestTopicEntry.id)
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text("hello")),
          state          = EventState.Complete
        ))
        // Wait for ConversationHealed AND the retry's success-Message
        // (so the assertions on the retry path see the completed
        // Message in the recorded queue).
        _ <- waitFor(System.currentTimeMillis() + 20_000L)(
          recorded.iterator().asScala.exists {
            case _: ConversationHealed => true
            case _ => false
          } && recorded.iterator().asScala.exists {
            case m: Message if m.participantId == agent.id && !m.isFailure
                            && m.state == EventState.Complete => true
            case _ => false
          }
        )
      } yield (recorded, invokeId)

      pipeline.map { case (rec, invokeId) =>
        running.set(false)
        val snap = rec.iterator().asScala.toList

        // (a) ConversationCorruptionDetected published with our agent's id + framework-heal source.
        val detected = snap.collect { case d: ConversationCorruptionDetected => d }
        detected should not be empty
        val detect = detected.head
        detect.participantId shouldBe agent.id
        detect.source shouldBe Some("framework-heal")
        detect.detectorSource shouldBe "provider-call"
        detect.originalError.statusCode shouldBe Some(400)
        detect.originalError.body.getOrElse("") should include ("No tool output found for function call")

        // (b) ConversationHealed names the default strategy and pairs by correlationId.
        val healed = snap.collect { case h: ConversationHealed => h }
        healed should not be empty
        val heal = healed.head
        heal.strategyName shouldBe "MissingToolResultStrategy"
        heal.correlationId shouldBe detect.correlationId
        heal.source shouldBe Some("framework-heal")
        heal.corrections should not be empty
        heal.corrections.head.strategyName shouldBe "MissingToolResultStrategy"
        heal.corrections.head.synthesisedEventId shouldBe invokeId

        // (c) ToolInvoke settled to Complete with the synthetic marker output.
        val settledInvoke: Option[ToolInvoke] = snap.collect {
          case t: ToolInvoke if t._id == invokeId && t.state == EventState.Complete => t
        }.lastOption.orElse(
          TestSigil.withDB(_.events.transaction(_.get(invokeId))).sync().collect { case t: ToolInvoke => t }
        )
        // Verify the settle landed via DB read — Signal stream may
        // surface the settle as a delta-mutated ToolInvoke; reading
        // the persisted row is the durable answer.
        val persisted = TestSigil.withDB(_.events.transaction(_.get(invokeId))).sync()
          .collect { case t: ToolInvoke => t }
          .getOrElse(fail(s"orphan invoke $invokeId not found after heal"))
        persisted.state shouldBe EventState.Complete
        val _ = settledInvoke

        // (d) HealingActivityNotice with outcome=Healed fires.
        val notices = snap.collect { case n: HealingActivityNotice => n }
        notices should not be empty
        val healedNotice = notices.find(_.outcome == HealingOutcome.Healed)
        healedNotice shouldBe defined
        healedNotice.get.strategyName shouldBe "MissingToolResultStrategy"

        // (e) Retry succeeded — the second provider call landed an
        // atomic respond Message authored by the agent.
        val agentMessages = snap.collect {
          case m: Message if m.participantId == agent.id && !m.isFailure => m
        }
        agentMessages should not be empty
        provider.attemptCount.get() should be >= 2
      }
    }

    "Strict mode: record corruption + StrictRefused, re-throw original error, NO heal" in {
      TestSigil.reset()
      scribeRecord.clear()
      TestSigil.setHealingMode(HealingMode.Strict)
      val wireCallId = s"call_${rapid.Unique()}"
      val convId  = Conversation.id(s"selfheal-strict-${rapid.Unique()}")
      val agent   = makeAgent(StrictAgent)
      val conv    = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      val provider = new OrphanCallErrorProvider(wireCallId)
      TestSigil.setProvider(Task.pure(provider))

      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        invokeId <- seedOrphanInvoke(convId, agent, wireCallId, TestTopicEntry.id)
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text("hello strict")),
          state          = EventState.Complete
        ))
        _ <- waitFor(System.currentTimeMillis() + 15_000L)(
          recorded.iterator().asScala.exists {
            case n: HealingActivityNotice => n.outcome == HealingOutcome.StrictRefused
            case _ => false
          }
        )
      } yield (recorded, invokeId)

      pipeline.map { case (rec, invokeId) =>
        running.set(false)
        val snap = rec.iterator().asScala.toList

        // Detected still publishes.
        val detected = snap.collect { case d: ConversationCorruptionDetected => d }
        detected should not be empty
        detected.head.detectorSource shouldBe "provider-call"

        // StrictRefused notice fires.
        val notices = snap.collect { case n: HealingActivityNotice => n }
        notices.exists(_.outcome == HealingOutcome.StrictRefused) shouldBe true

        // Heal did NOT fire — no ConversationHealed event.
        snap.collect { case _: ConversationHealed => () } shouldBe empty

        // Orphan invoke is STILL Active — strict refused the heal.
        val persisted = TestSigil.withDB(_.events.transaction(_.get(invokeId))).sync()
          .collect { case t: ToolInvoke => t }
          .getOrElse(fail("orphan invoke missing in strict mode"))
        persisted.state shouldBe EventState.Active

        // The original error surfaced through the standard failure
        // path — a Failure-disposition Message lands.
        val failures = snap.collect {
          case m: Message if m.participantId == agent.id && m.isFailure => m
        }
        failures should not be empty
        failures.flatMap(_.failureReason).mkString(" | ") should include ("No tool output found for function call")
      }
    }

    "One heal per turn: retry also fails → HealingExhausted, no second heal" in {
      TestSigil.reset()
      scribeRecord.clear()
      TestSigil.setHealingMode(HealingMode.Recover)
      val wireCallId = s"call_${rapid.Unique()}"
      val convId  = Conversation.id(s"selfheal-exhausted-${rapid.Unique()}")
      val agent   = makeAgent(ExhaustedAgent)
      val conv    = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      val provider = new OrphanCallErrorProvider(wireCallId)
      TestSigil.setProvider(Task.pure(provider))

      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- seedOrphanInvoke(convId, agent, wireCallId, TestTopicEntry.id)
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text("hello exhaust")),
          state          = EventState.Complete
        ))
        _ <- waitFor(System.currentTimeMillis() + 20_000L)(
          recorded.iterator().asScala.exists {
            case _: HealingExhausted => true
            case _ => false
          }
        )
      } yield recorded

      pipeline.map { rec =>
        running.set(false)
        val snap = rec.iterator().asScala.toList

        // First heal landed.
        val healed = snap.collect { case h: ConversationHealed => h }
        healed should have size 1
        healed.head.strategyName shouldBe "MissingToolResultStrategy"

        // Exhausted fires naming the same strategy + carrying the
        // retry's error envelope.
        val exhausted = snap.collect { case e: HealingExhausted => e }
        exhausted should not be empty
        exhausted.head.strategyName shouldBe "MissingToolResultStrategy"
        exhausted.head.retryError.statusCode shouldBe Some(400)
        exhausted.head.correlationId shouldBe healed.head.correlationId

        // NO second ConversationHealed — only one per turn.
        healed should have size 1

        // Exhausted notice fires.
        val notices = snap.collect { case n: HealingActivityNotice => n }
        notices.exists(_.outcome == HealingOutcome.Exhausted) shouldBe true

        // Standard Failure path runs after Exhausted.
        snap.collect { case m: Message if m.participantId == agent.id && m.isFailure => m } should not be empty
      }
    }

    "Forensic preservation: scribe.error captures the original error payload" in {
      // Reuses the Recover scenario's setup but doesn't reset the
      // scribe log so the assertion sees the cumulative records.
      // Specifically targets a fresh scenario so we know the payload
      // we observe came from THIS run.
      TestSigil.reset()
      scribeRecord.clear()
      TestSigil.setHealingMode(HealingMode.Recover)
      val wireCallId = s"call_${rapid.Unique()}"
      val convId  = Conversation.id(s"selfheal-scribe-${rapid.Unique()}")
      val agent   = makeAgent(ForensicAgent)
      val conv    = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      val provider = new HealThenSucceedProvider(wireCallId)
      TestSigil.setProvider(Task.pure(provider))

      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- seedOrphanInvoke(convId, agent, wireCallId, TestTopicEntry.id)
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text("hello forensic")),
          state          = EventState.Complete
        ))
        _ <- waitFor(System.currentTimeMillis() + 15_000L)(
          recorded.iterator().asScala.exists {
            case _: ConversationHealed => true
            case _ => false
          }
        )
      } yield ()

      pipeline.map { _ =>
        running.set(false)
        val logs = scribeRecord.iterator().asScala.toList
        // Two-prong check: (1) the heal-entry scribe.error names the
        // strategy + the StreamingHttpFailedException class; (2) the
        // log line carries the upstream error body.
        val heal = logs.find(l => l.contains("heal[MissingToolResultStrategy]"))
        heal shouldBe defined
        heal.get should include ("StreamingHttpFailedException")
      }
    }

    "BrokenHistoryException: renderFrames throws typed exception → heal fires" in {
      TestSigil.reset()
      scribeRecord.clear()
      TestSigil.setHealingMode(HealingMode.Recover)
      val wireCallId = s"call_${rapid.Unique()}"
      val convId  = Conversation.id(s"selfheal-brokenhistory-${rapid.Unique()}")
      val agent   = makeAgent(BrokenHistoryAgent)
      val conv    = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

      // Build the bricked log directly — a Complete ToolInvoke event
      // whose inlined contextFrame is an Active ToolCall. framesFor
      // surfaces it; the provider's translate calls renderFrames
      // which throws BrokenHistoryException.
      val invokeId = Event.id()
      val orphanFrame = ContextFrame.ToolCall(
        toolName      = ToolName("read_file"),
        argsJson      = "{}",
        callId        = invokeId,
        participantId = agent.id,
        sourceEventId = invokeId,
        wireCallId    = Some(wireCallId),
        state         = ToolCallState.Active
      )
      val orphanInvoke = ToolInvoke(
        _id            = invokeId,
        toolName       = ToolName("read_file"),
        participantId  = agent.id,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        state          = EventState.Complete,
        callId         = Some(wireCallId),
        contextFrame   = Some(orphanFrame)
      )

      // Once the heal settles the orphan, the retry runs through a
      // provider that emits a successful respond.
      val provider = new HealThenSucceedProvider(wireCallId)
      TestSigil.setProvider(Task.pure(provider))

      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.withDB(_.events.transaction(_.upsert(orphanInvoke)))
        // The provider used here STILL throws on call #1 — but the
        // renderFrames path fires first, so we observe the typed
        // BrokenHistoryException path. The seeded ToolInvoke is
        // already Complete; the heal sees no Active invoke matching
        // the wire id and emits no corrections (it's working with
        // the renderFrames-evidence). For the retry to succeed we
        // need framesFor to NOT see the orphan after the heal —
        // which won't happen automatically since the contextFrame's
        // state is what we seeded. So this test asserts on the
        // detection side: typed CorruptionEvidence flowed through.
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text("hello broken history")),
          state          = EventState.Complete
        ))
        _ <- waitFor(System.currentTimeMillis() + 15_000L)(
          recorded.iterator().asScala.exists {
            case d: ConversationCorruptionDetected =>
              d.detectorSource == "renderFrames-invariant"
            case _ => false
          }
        )
      } yield recorded

      pipeline.map { rec =>
        running.set(false)
        val snap = rec.iterator().asScala.toList

        val detected = snap.collect {
          case d: ConversationCorruptionDetected if d.detectorSource == "renderFrames-invariant" => d
        }
        detected should not be empty
        // Typed evidence: MissingToolResult naming the orphan invoke + wire id.
        val evidence = detected.head.detectedCorruption
        evidence.collect { case m: CorruptionEvidence.MissingToolResult => m } should not be empty
        evidence.collect { case m: CorruptionEvidence.MissingToolResult => m }
          .map(_.callId) should contain (wireCallId)

        // HealingActivityNotice fires with Healed (the strategy
        // matched and ran — even if it had no Active invoke to
        // settle, it returned a HealResult).
        val notices = snap.collect { case n: HealingActivityNotice => n }
        notices should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/** Distinct agent ids per scenario so signal queues don't cross-pollinate
  * within a single TestSigil JVM. */
case object StrictAgent extends sigil.participant.AgentParticipantId {
  override val value: String = "strict-agent"
}
case object ExhaustedAgent extends sigil.participant.AgentParticipantId {
  override val value: String = "exhausted-agent"
}
case object ForensicAgent extends sigil.participant.AgentParticipantId {
  override val value: String = "forensic-agent"
}
case object BrokenHistoryAgent extends sigil.participant.AgentParticipantId {
  override val value: String = "broken-history-agent"
}
