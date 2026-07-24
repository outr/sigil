package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ContextFrame, ToolCallState}
import sigil.db.Model
import sigil.event.{Message, MessageRole, Stop, ToolInvoke, ToolOutcome}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.signal.{EventState, Signal, ToolProgress}
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The DETACHED tool contract: a [[sigil.tool.Tool.detachable]] tool
 * whose execution outlives the detach threshold releases the
 * conversation instead of holding the turn hostage —
 *
 *   1. the invoke settles mid-turn with a tracking handle, the turn
 *      ends, the work continues, and progress keeps flowing on the
 *      original invoke id;
 *   2. completion folds the real result onto the invoke and a
 *      continuation trigger re-invokes the agent with the summary in
 *      its frames;
 *   3. Stop cancels the detached task via its checkpoint; the invoke
 *      settles and NO continuation fires;
 *   4. sub-threshold and non-detachable executions are unchanged.
 */
class DetachedToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "detached-tool-model")
  TestSigil.testModel(modelId)

  /**
   * Intra-turn compaction issues its own LLM call at respond
   * boundaries — this spec counts scripted-provider round-trips
   * exactly, so compaction is disabled for the suite.
   */
  private object NoCompaction extends sigil.conversation.compression.IntraTurnCompactor {
    override def shouldCompact(turnEvents: Vector[sigil.event.Event], estimatedTokens: Long, threshold: Long): Boolean = false
    override def selectFoldable(turnEvents: Vector[sigil.event.Event],
                                ctx: sigil.conversation.compression.TurnEventsContext): List[Id[sigil.event.Event]] = Nil
  }
  TestSigil.intraTurnCompactorOverride.set(Some(NoCompaction))

  /**
   * The per-turn memory extractor also issues its own provider call
   * after each turn — same call-count pollution; disabled per turn
   * start (reset() restores the default, so this is re-applied).
   */
  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[_root_.sigil.participant.ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }

  /**
   * Per-call scripted provider. `calls` counts provider round-trips —
   * a continuation turn shows up as an extra call.
   */
  private class ScriptedProvider(script: Int => Stream[ProviderEvent]) extends Provider {
    val calls = new atomic.AtomicInteger(0)
    val inputs = new ConcurrentLinkedQueue[ProviderCall]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = calls.incrementAndGet()
      inputs.add(input)
      script(n)
    }
  }

  /**
   * Calls the given fixture tool once, then answers every later call
   * with a terminal respond.
   */
  final private class ToolThenRespondProvider(toolName: String) extends Provider {
    val calls = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (calls.incrementAndGet() == 1) {
        val cid = CallId(s"tool-${rapid.Unique()}")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, toolName),
          ProviderEvent.ToolCallComplete(cid, SlowStopInput()),
          ProviderEvent.Done(StopReason.Complete)
        ))
      } else {
        val cid = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.ToolCallComplete(
            cid,
            RespondInput(
              topicLabel = TestTopicEntry.label,
              topicSummary = TestTopicEntry.summary,
              content = "Acknowledged.",
              endsTurn = true
            )),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
  }

  private def makeAgent(toolName: ToolName): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = toolName :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def startTurn(tool: SlowStopToolBase, thresholdMs: Long): Task[(Id[Conversation], ToolThenRespondProvider)] = {
    TestSigil.reset()
    TestSigil.setMemoryExtractor(NoExtraction)
    tool.reset()
    TestSigil.toolDetachThresholdOverride.set(Some(thresholdMs))
    val provider = new ToolThenRespondProvider(tool.name.value)
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"detached-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent(tool.name)), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Run the sweep.")),
        state = EventState.Complete
      ))
    } yield (convId, provider)
  }

  private def invokeFor(convId: Id[Conversation], toolName: ToolName): Task[Option[ToolInvoke]] =
    TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.collectFirst {
      case ti: ToolInvoke if ti.conversationId == convId && ti.toolName == toolName => ti
    })

  private def waitFor(deadlineMs: Long)(pred: => Task[Boolean]): Task[Unit] =
    pred.flatMap { ok =>
      if (ok || System.currentTimeMillis() > deadlineMs) Task.unit
      else Task.sleep(50.millis).flatMap(_ => waitFor(deadlineMs)(pred))
    }

  private def startRecorder(): (ConcurrentLinkedQueue[Signal], atomic.AtomicBoolean) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    (recorded, running)
  }

  "a detachable tool crossing the threshold" should {

    "settle with a handle mid-turn, keep progress flowing, and deliver the result via a continuation turn" in {
      val tool = DetachableSweepTool
      val (recorded, running) = startRecorder()
      for {
        pair <- startTurn(tool, thresholdMs = 200L)
        (convId, provider) = pair
        _ <- Task(tool.midwayLatch.await(10, TimeUnit.SECONDS))
        // Detach fires ~200ms in; the turn then finishes with a respond.
        _ <- waitFor(System.currentTimeMillis() + 10000L)(
          invokeFor(convId, tool.name).map(_.exists(ti => ti.detached && ti.state == EventState.Complete))
        )
        detachedRow <- invokeFor(convId, tool.name)
        // Turn ended: the respond happened (provider call 2) while the
        // tool still holds its latch.
        _ <- waitFor(System.currentTimeMillis() + 10000L)(Task.pure(provider.calls.get() >= 2))
        turnEndedCalls = provider.calls.get()
        panel <- TestSigil.activeTasksFor(convId)
        // Release the tool — progress pulse flows on the ORIGINAL
        // invoke id, then completion folds the real result and the
        // continuation turn fires (provider call 3).
        _ <- Task(tool.proceedLatch.countDown())
        _ <- waitFor(System.currentTimeMillis() + 10000L)(
          invokeFor(convId, tool.name).map(_.exists(_.outcome == ToolOutcome.Success))
        )
        _ <- Task {
          println("=== TEST1 RECORDED SIGNALS ===")
          recorded.iterator().asScala.toList.foreach { sig =>
            val extra = sig match {
              case ti: ToolInvoke => s" tool=${ti.toolName.value} outcome=${ti.outcome} detached=${ti.detached} state=${ti.state}"
              case td: sigil.signal.ToolDelta =>
                s" target=${td.target.value.take(8)} state=${td.state} outcome=${td.outcome} detached=${td.detached}"
              case m: Message => s" role=${m.role} from=${m.participantId.value} text=${m.content.collect { case ResponseContent.Text(t) =>
                    t
                  }.mkString.take(60)}"
              case _ => ""
            }
            println(s"  ${sig.getClass.getSimpleName}$extra")
          }
        }
        _ <- waitFor(System.currentTimeMillis() + 15000L)(Task.pure(provider.calls.get() >= 3))
        settledRow <- invokeFor(convId, tool.name)
        // Let the continuation turn fully settle so its loop can't leak
        // provider calls into the next test.
        _ <- TestSigil.awaitSettled(convId)
        _ <- Task.sleep(300.millis)
      } yield {
        running.set(false)
        TestSigil.toolDetachThresholdOverride.set(None)
        // Detached settle: Complete, outcome still Pending, handle text.
        withClue(s"detachedRow=$detachedRow: ") {
          detachedRow.map(_.detached) shouldBe Some(true)
          detachedRow.map(_.outcome) shouldBe Some(ToolOutcome.Pending)
          detachedRow.map(_.summary).getOrElse("") should include("running in the background as task")
        }
        // The tool had NOT finished when the turn ended.
        tool.stepsRun.get() should be >= 2
        turnEndedCalls should be >= 2
        // Panel carried the detached task while it ran.
        panel.map(_.workflowSourceId) should contain("detached-tool")
        // Progress pulse arrived on the original invoke id after detach.
        val invokeId = detachedRow.get._id
        recorded.iterator().asScala.collect {
          case tp: ToolProgress if tp.invokeId == invokeId => tp
        }.toList should not be empty
        // Completion folded the real result onto the same invoke; the
        // frame carries the full summary for the continuation prompt.
        withClue(s"settledRow=$settledRow: ") {
          settledRow.map(_.outcome) shouldBe Some(ToolOutcome.Success)
          settledRow.flatMap(_.contextFrame).collect {
            case tc: ContextFrame.ToolCall => tc.state
          } match {
            case Some(ToolCallState.Complete(content, _)) => content should include("completed 6 steps")
            case other => fail(s"expected settled Complete frame, got $other")
          }
        }
        // Continuation turn happened (call 3+) and the tool ran once.
        provider.calls.get() should be >= 3
        tool.stepsRun.get() shouldBe 6
      }
    }
  }

  "Stop during a detached task" should {

    "cancel at the checkpoint, settle the invoke, and fire NO continuation" in {
      val tool = DetachableSweepTool
      for {
        pair <- startTurn(tool, thresholdMs = 200L)
        (convId, provider) = pair
        _ <- Task(tool.midwayLatch.await(10, TimeUnit.SECONDS))
        _ <- waitFor(System.currentTimeMillis() + 10000L)(
          invokeFor(convId, tool.name).map(_.exists(_.detached))
        )
        // The turn finishes; then the user stops the conversation.
        _ <- waitFor(System.currentTimeMillis() + 10000L)(Task.pure(provider.calls.get() >= 2))
        _ <- TestSigil.awaitSettled(convId)
        _ <- TestSigil.publish(Stop(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          force = false,
          reason = Some("stopped by user")
        ))
        _ <- Task(tool.proceedLatch.countDown())
        // The checkpoint observes cancellation → Failure settle.
        _ <- waitFor(System.currentTimeMillis() + 10000L)(
          invokeFor(convId, tool.name).map(_.exists(_.outcome != ToolOutcome.Pending))
        )
        settled <- invokeFor(convId, tool.name)
        _ <- Task.sleep(500.millis) // window for a (wrong) continuation to fire
        events <- TestSigil.withDB(_.eventsTransaction(convId)(_.list))
      } yield {
        TestSigil.toolDetachThresholdOverride.set(None)
        // Steps 3-6 never ran; the invoke settled as a Failure.
        tool.stepsRun.get() shouldBe 2
        withClue(s"settled=$settled: ") {
          settled.exists(_.outcome.isInstanceOf[ToolOutcome.Failure]) shouldBe true
        }
        // No continuation trigger was published.
        events.collect {
          case m: Message
              if m.role == MessageRole.Tool && m.origin.contains(settled.get._id)
                && m.content.collect { case ResponseContent.Text(t) => t }.mkString.contains("completed") => m
        } shouldBe empty
        // And no continuation turn ran.
        provider.calls.get() shouldBe 2
      }
    }
  }

  "sub-threshold and non-detachable executions" should {

    "run a fast detachable tool fully synchronously" in {
      val tool = FastDetachableTool
      for {
        pair <- startTurn(tool, thresholdMs = 10000L)
        (convId, provider) = pair
        _ <- waitFor(System.currentTimeMillis() + 10000L)(Task.pure(provider.calls.get() >= 2))
        row <- invokeFor(convId, tool.name)
        _ <- TestSigil.awaitSettled(convId)
        _ <- Task.sleep(200.millis)
      } yield {
        TestSigil.toolDetachThresholdOverride.set(None)
        withClue(s"row=$row: ") {
          row.map(_.detached) shouldBe Some(false)
          row.map(_.outcome) shouldBe Some(ToolOutcome.Success)
        }
      }
    }

    "block on a non-detachable slow tool exactly as before" in {
      val tool = SlowCooperativeTool
      for {
        pair <- startTurn(tool, thresholdMs = 200L)
        (convId, provider) = pair
        _ <- Task(tool.midwayLatch.await(10, TimeUnit.SECONDS))
        // Well past the threshold: still no detach, still no respond —
        // the turn is (correctly) blocked on the tool.
        _ <- Task.sleep(600.millis)
        // Mid-execution nothing is durable for a BLOCKING tool (the
        // iteration batch owns the write, #416) — in particular no
        // detach settle was committed.
        midRow <- invokeFor(convId, tool.name)
        callsWhileBlocked = provider.calls.get()
        _ <- Task(tool.proceedLatch.countDown())
        _ <- waitFor(System.currentTimeMillis() + 10000L)(
          invokeFor(convId, tool.name).map(_.exists(_.outcome == ToolOutcome.Success))
        )
        finalRow <- invokeFor(convId, tool.name)
        _ <- TestSigil.awaitSettled(convId)
      } yield {
        TestSigil.toolDetachThresholdOverride.set(None)
        midRow.flatMap(r => Option.when(r.detached)(r)) shouldBe None
        callsWhileBlocked shouldBe 1
        finalRow.map(_.detached) shouldBe Some(false)
        finalRow.map(_.outcome) shouldBe Some(ToolOutcome.Success)
      }
    }
  }

  "a completion landing while the agent is mid-turn" should {

    "queue the continuation behind the active turn, never interleaving it" in {
      // Provider script: call 1 = detachable sweep (turn 1), call 2 =
      // respond (turn 1 ends), call 3 = slow cooperative tool (turn 2),
      // calls 4+ = respond. The detached completion lands while turn 2
      // is parked on its tool — the continuation must wait for turn 2's
      // own respond before being consumed.
      val sweep = DetachableSweepTool
      val slow = SlowCooperativeTool
      TestSigil.reset()
      TestSigil.setMemoryExtractor(NoExtraction)
      sweep.reset()
      slow.reset()
      TestSigil.toolDetachThresholdOverride.set(Some(200L))
      val provider = new ScriptedProvider({
        case 1 =>
          val cid = CallId(s"sweep-${rapid.Unique()}")
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ToolCallStart(cid, sweep.name.value),
            ProviderEvent.ToolCallComplete(cid, SlowStopInput()),
            ProviderEvent.Done(StopReason.Complete)
          ))
        case 3 =>
          val cid = CallId(s"slow-${rapid.Unique()}")
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ToolCallStart(cid, slow.name.value),
            ProviderEvent.ToolCallComplete(cid, SlowStopInput()),
            ProviderEvent.Done(StopReason.Complete)
          ))
        case _ =>
          val cid = CallId(s"respond-${rapid.Unique()}")
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(
              cid,
              RespondInput(
                topicLabel = TestTopicEntry.label,
                topicSummary = TestTopicEntry.summary,
                content = "Done for now.",
                endsTurn = true
              )),
            ProviderEvent.Done(StopReason.Complete)
          ))
      })
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"detached-queue-${rapid.Unique()}")
      // The agent needs BOTH fixture tools in its roster — turn 1 runs
      // the sweep, turn 2 the slow cooperative tool.
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = modelId,
        toolNames = sweep.name :: slow.name :: CoreTools.coreToolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
      )
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      def userMsg(text: String) = TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text(text)),
        state = EventState.Complete
      ))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- userMsg("Run the sweep.")
        _ <- Task(sweep.midwayLatch.await(10, TimeUnit.SECONDS))
        // Turn 1 detaches and ends.
        _ <- waitFor(System.currentTimeMillis() + 10000L)(Task.pure(provider.calls.get() >= 2))
        _ <- TestSigil.awaitSettled(convId)
        // Turn 2 starts and parks on its own slow tool.
        _ <- userMsg("Now do something else.")
        _ <- Task(slow.midwayLatch.await(10, TimeUnit.SECONDS))
        // The detached completion lands MID-TURN-2.
        _ <- Task(sweep.proceedLatch.countDown())
        _ <- waitFor(System.currentTimeMillis() + 10000L)(
          invokeFor(convId, sweep.name).map(_.exists(_.outcome == ToolOutcome.Success))
        )
        _ <- Task.sleep(400.millis) // window for a (wrong) interleaved provider call
        callsWhileTurn2Blocked = provider.calls.get()
        // Turn 2 finishes; the queued continuation is consumed at the
        // next natural boundary — turn 2's own closing iteration.
        _ <- Task(slow.proceedLatch.countDown())
        _ <- waitFor(System.currentTimeMillis() + 15000L)(Task.pure(provider.calls.get() >= 4))
        _ <- TestSigil.awaitSettled(convId)
        _ <- Task.sleep(300.millis)
      } yield {
        TestSigil.toolDetachThresholdOverride.set(None)
        // While turn 2 was blocked on its tool, no extra provider call
        // interleaved — the continuation waited.
        callsWhileTurn2Blocked shouldBe 3
        // The completion was delivered at the next iteration boundary:
        // the closing iteration's prompt carries the sweep's settled
        // result (the invoke frame folded the real output).
        val closing = provider.inputs.iterator().asScala.toList.lift(3)
          .getOrElse(fail("no fourth provider call recorded"))
        val renderedHistory = closing.messages.map(_.toString).mkString("\n")
        renderedHistory should include("completed 6 steps")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.intraTurnCompactorOverride.set(None)
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
