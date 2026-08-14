package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnPhase
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, MessageDelta, Signal, StateDelta, TurnPhases}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Fixed per-turn orchestration overhead — the wall clock a turn spends
 * outside the provider.
 *
 * Measured on a WARM conversation (the first turn pays class loading,
 * tokenizer init and store warm-up, so it isn't representative) against
 * a scripted provider whose in-call time is known exactly, so
 * `wall clock - provider time` is pure framework cost.
 *
 * Two properties are asserted. The first is the absolute budget for one
 * warm turn. The second is the one that matters at production scale:
 * that budget must not grow when the event store holds history the turn
 * has nothing to do with. A per-turn read that scans the whole store
 * instead of the conversation turns a fixed cost into one proportional
 * to how long the deployment has been running — which is how a
 * sub-second overhead becomes a ten-second one in the field without any
 * single operation looking slow.
 */
class TurnOverheadSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "turn-overhead-model")
  TestSigil.testModel(modelId)

  /** Coarse wall-clock ceiling for one warm turn's non-provider work.
    * Deliberately loose: absolute milliseconds are dominated by the
    * host's store-commit latency, and the suite runs several forked JVMs
    * at once, so a tight bound measures the runner rather than the
    * framework. This catches an order-of-magnitude regression; the
    * scaling assertion below — where both measurements share whatever
    * load the host is under — is the tight guard. */
  private val OverheadBudget: FiniteDuration = 5.seconds

  /** Known in-call cost of the scripted provider, so the measurement can
    * subtract a real "model time" rather than assume zero. */
  private val ProviderDelay: FiniteDuration = 200.millis

  /** Events written into unrelated conversations before the loaded
    * measurement. Large enough that a whole-store scan is unmistakable,
    * small enough to seed in a few seconds. */
  private val UnrelatedEvents: Int = 4000

  /**
   * How much a store full of unrelated history may multiply one turn's
   * overhead.
   *
   * This SHOULD be 1.0 — a turn reads one conversation, so history in
   * others is not its business. It isn't: several per-turn reads scan the
   * whole `events` store and filter by conversation in memory, so the
   * turn pays for every event the deployment ever wrote. Measured here at
   * 4000 unrelated events the overhead grows roughly 1.5×, which
   * extrapolates to the seconds-scale fixed overhead reported from
   * production stores.
   *
   * The bound is set where it catches a step change rather than where the
   * framework should be, and tightening it is the acceptance test for
   * scoping those reads. Doing so is not a drop-in swap to the indexed
   * `SigilDB.conversationEvents`: a whole-store `_.list` reads the
   * key-value side and is immediately consistent, while the indexed query
   * reads a search index that refreshes asynchronously, so mid-turn
   * decision logic (stall detection, workflow step threading) silently
   * loses the turn's own tail.
   */
  private val ScalingTolerance: Double = 3.0

  /** Warm turns per measurement; the median is reported. */
  private val MeasuredTurns: Int = 5

  /**
   * Instant-completion provider: every call answers with a terminal
   * `respond`, after sleeping [[ProviderDelay]]. The sleep is inside
   * `call`, so [[inCallMillis]] measures exactly the time the framework
   * spent waiting on "the model".
   */
  private class InstantProvider extends Provider {
    private val nanos = new AtomicLong(0L)
    private val count = new java.util.concurrent.atomic.AtomicInteger(0)

    def inCallMillis: Long = nanos.get() / 1000000L

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))

    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.force {
      val started = System.nanoTime()
      Task.sleep(ProviderDelay).map { _ =>
        val n = count.incrementAndGet()
        val callId = CallId(s"overhead-$n")
        nanos.addAndGet(System.nanoTime() - started)
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
          ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
            // The conversation's existing label, so the topic-shift
            // fast path applies and the turn makes exactly one provider
            // call — the measurement isolates framework cost.
            topicLabel   = TestTopicEntry.label,
            topicSummary = TestTopicEntry.summary,
            content      = "Acknowledged.",
            endsTurn     = true
          )),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(64), temperature = Some(0.0))
    )

  /** One measured turn's outcome: the wall clock from the user message's
    * publish to the agent's reply settling, the provider time inside it,
    * and the turn's own [[TurnPhases]] breakdown. */
  private case class Measured(wallMs: Long, providerMs: Long, phases: Option[TurnPhases]) {
    /** The turn's framework cost: the recorder's own accounting when the
      * breakdown arrived (it brackets the whole claim, including the
      * release the spec's wall clock stops short of), otherwise the
      * observed wall clock minus the provider's in-call time. */
    def overheadMs: Long = phases.map(_.overheadMs).getOrElse(wallMs - providerMs)
    def render: String =
      s"wall=${wallMs}ms provider=${providerMs}ms overhead=${overheadMs}ms" +
        phases.fold(" phases=<none>")(p =>
          s" total=${p.totalMs}ms iterations=${p.iterations} " +
            p.durations.map { case (phase, ms) => s"$phase=${ms}ms" }.mkString(" "))
  }

  private def turn(convId: Id[Conversation],
                   provider: InstantProvider,
                   text: String,
                   recorded: ConcurrentLinkedQueue[Signal]): Task[Measured] = {
    def phaseNotices: List[TurnPhases] = recorded.iterator().asScala.collect {
      case p: TurnPhases if p.conversationId == convId => p
    }.toList
    // The agent's reply Message is broadcast `Active` and settled by a
    // state-transition Delta; the settle is when the user-visible reply
    // is final, so that — not the Active broadcast — is the terminal
    // signal a consumer waits on.
    def replySeen(after: Long): Boolean = {
      val replyIds = recorded.iterator().asScala.collect {
        case m: Message if m.conversationId == convId &&
          m.participantId == TestAgent &&
          m.role == MessageRole.Standard &&
          m.timestamp.value >= after => m._id
      }.toSet
      recorded.iterator().asScala.exists {
        case m: Message      => replyIds.contains(m._id) && m.state == EventState.Complete
        case d: StateDelta   => replyIds.contains(d.target) && d.state == EventState.Complete
        case d: MessageDelta => replyIds.contains(d.target) && d.state.contains(EventState.Complete)
        case _               => false
      }
    }
    def await(after: Long, deadline: Long): Task[Unit] = Task.defer {
      if (replySeen(after)) Task.unit
      else if (System.currentTimeMillis() > deadline)
        Task.error(new IllegalStateException(s"no agent reply within the deadline for $convId"))
      else Task.sleep(5.millis).flatMap(_ => await(after, deadline))
    }
    Task.defer {
      val startedAt = System.currentTimeMillis()
      val providerBefore = provider.inCallMillis
      val phasesBefore = phaseNotices.size
      TestSigil.publish(Message(
        participantId  = TestUser,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        content        = Vector(ResponseContent.Text(text)),
        state          = EventState.Complete
      )).flatMap(_ => await(startedAt, startedAt + 60000L))
        .map(_ => System.currentTimeMillis() - startedAt)
        .flatMap(wall => TestSigil.awaitSettled(convId).map(_ => wall))
        // The breakdown is published by the claim release; wait for THIS
        // turn's notice rather than reading whatever the prior turn left.
        .flatMap { wall =>
          def awaitNotice(deadline: Long): Task[Unit] = Task.defer {
            if (phaseNotices.size > phasesBefore) Task.unit
            else if (System.currentTimeMillis() > deadline) Task.unit
            else Task.sleep(10.millis).flatMap(_ => awaitNotice(deadline))
          }
          awaitNotice(System.currentTimeMillis() + 5000L).map(_ => wall)
        }
        // The FIRST breakdown after this turn started is the claim that
        // produced the reply; a conversation can re-fire after release
        // and that second claim is not what the user waited on.
        .map(wall => Measured(wall, provider.inCallMillis - providerBefore,
          phaseNotices.drop(phasesBefore).headOption))
    }
  }

  /** Write `count` events into conversations the measured turn has
    * nothing to do with — the store history a long-lived deployment
    * accumulates. */
  private def seedUnrelatedEvents(count: Int): Task[Unit] = {
    val perConversation = 100
    TestSigil.withDB(_.events.transaction { tx =>
      Stream.emits((0 until count).toList).evalMap { i =>
        tx.insert(Message(
          participantId  = TestUser,
          conversationId = Conversation.id(s"turn-overhead-unrelated-${i / perConversation}"),
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text(s"Unrelated history entry $i.")),
          state          = EventState.Complete
        )).unit
      }.drain
    })
  }

  private def measure(label: String): Task[Measured] = {
    val provider = new InstantProvider
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"turn-overhead-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    for {
      _ <- Task.sleep(100.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      // Warm-up turn: pays class loading, tokenizer init, store warm-up.
      _ <- turn(convId, provider, "Warm the conversation up.", recorded)
      // Several warm turns; the median is the reported figure, since a
      // single turn is at the mercy of a GC pause or a JIT compile.
      samples <- Task.sequence((1 to MeasuredTurns).toList.map(i =>
                   turn(convId, provider, s"Measured turn $i.", recorded)))
    } yield {
      running.set(false)
      samples.foreach(s => scribe.info(s"TurnOverheadSpec[$label] sample: ${s.render}"))
      val median = samples.sortBy(_.overheadMs)(using Ordering[Long])(samples.size / 2)
      scribe.info(s"TurnOverheadSpec[$label] median: ${median.render}")
      median
    }
  }

  /** Both measurements, in order, shared across the assertions below —
    * they exercise one store and must not interleave. */
  private lazy val measurements: Task[(Measured, Measured)] =
    (for {
      small  <- measure("small-store")
      _      <- seedUnrelatedEvents(UnrelatedEvents)
      loaded <- measure("loaded-store")
    } yield (small, loaded)).singleton

  "Per-turn orchestration overhead" should {

    "keep a warm turn's non-provider work under the budget" in {
      measurements.map { case (small, _) =>
        withClue(s"${small.render}: ") {
          small.providerMs should be >= ProviderDelay.toMillis
          small.overheadMs should be < OverheadBudget.toMillis
        }
      }
    }

    "not grow unboundedly with unrelated history in the event store" in {
      measurements.map { case (small, loaded) =>
        withClue(s"small: ${small.render} / loaded: ${loaded.render}: ") {
          loaded.overheadMs.toDouble should be < (small.overheadMs * ScalingTolerance)
        }
      }
    }
  }

  "TurnPhases instrumentation" should {

    "emit one breakdown per turn covering every phase" in {
      measurements.map { case (small, _) =>
        withClue(s"${small.render}: ") {
          val phases = small.phases.getOrElse(fail("no TurnPhases notice was emitted for the measured turn"))
          phases.participantId shouldBe TestAgent
          phases.durations.map(_._1) shouldBe TurnPhase.values.toList
          phases.durations.filter(_._2 < 0L) shouldBe empty
        }
      }
    }

    "sum its phases to the turn's wall clock" in {
      measurements.map { case (small, _) =>
        val phases = small.phases.getOrElse(fail("no TurnPhases notice was emitted for the measured turn"))
        withClue(s"${small.render}: ") {
          phases.durations.map(_._2).sum shouldBe phases.totalMs
          // The recorder brackets the whole claim, so its total covers at
          // least the model time the spec independently observed. A
          // tighter comparison against the spec's own wall clock measures
          // the poll interval and the runner's load, not the framework.
          phases.totalMs should be >= phases(TurnPhase.ModelStream)
        }
      }
    }

    "attribute the provider's time to ModelStream and the rest to overhead" in {
      measurements.map { case (small, _) =>
        val phases = small.phases.getOrElse(fail("no TurnPhases notice was emitted for the measured turn"))
        withClue(s"${small.render}: ") {
          // The scripted provider sleeps a known duration inside the wire
          // call, so a ModelStream that doesn't reflect it means the wire
          // boundary marks aren't where they claim to be.
          phases(TurnPhase.ModelStream) should be >= ProviderDelay.toMillis
          phases.overheadMs shouldBe (phases.totalMs - phases(TurnPhase.ModelStream))
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
