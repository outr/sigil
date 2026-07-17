package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, Stop, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/**
 * Stop vs. an IN-FLIGHT tool execution. Field observation: a
 * multi-minute batch tool kept running (and editing files) after the
 * user's Stop, and when it finished no ToolDelta ever settled its
 * invoke — the chip spun forever on a permanently-Active row. Two
 * guarantees pinned here:
 *
 *   1. COOPERATION — a tool that calls `ctx.checkpoint` at its batch
 *      boundaries stops at the first boundary after the user's Stop
 *      (remaining steps never run) and its invoke settles with a
 *      visible failure.
 *   2. SETTLEMENT — a tool that ignores Stop entirely (no checkpoint,
 *      swallows the force-interrupt) may grind to completion, but its
 *      invoke must STILL end settled: no stop route may leave an
 *      invoke Active forever.
 *
 * Plus the regression guard: an un-stopped turn settles exactly as
 * before.
 */
class StopInflightToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "stop-inflight-model")
  TestSigil.testModel(modelId)

  /**
   * Calls the given fixture tool once, then answers any later call with
   * a terminal respond (topic fast-path).
   */
  final private class ToolThenRespondProvider(toolName: String) extends Provider {
    private val calls = new java.util.concurrent.atomic.AtomicInteger(0)
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
              topicLabel = "Sweep done",
              topicSummary = "slow tool finished, replying",
              content = "Wrapped up.",
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

  private def startTurn(tool: SlowStopToolBase): Task[Id[Conversation]] = {
    TestSigil.reset()
    tool.reset()
    // `setProvider` takes its Task by name and re-evaluates it on every
    // model resolve — hoist the instance so the call counter survives
    // across the turn's iterations.
    val provider = new ToolThenRespondProvider(tool.name.value)
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"stop-inflight-${rapid.Unique()}")
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
    } yield convId
  }

  private def publishStop(convId: Id[Conversation], force: Boolean): Task[Unit] =
    TestSigil.publish(Stop(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      force = force,
      reason = Some("stopped by user")
    )).unit

  private def invokeFor(convId: Id[Conversation], toolName: ToolName): Task[Option[ToolInvoke]] =
    TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.collectFirst {
      case ti: ToolInvoke if ti.conversationId == convId && ti.toolName == toolName => ti
    })

  /**
   * Poll until the tool's invoke reaches Complete (or the deadline
   * passes — assertions then fail with the final state).
   */
  private def awaitInvokeSettled(convId: Id[Conversation],
                                 toolName: ToolName,
                                 deadline: FiniteDuration = 20.seconds): Task[Option[ToolInvoke]] = {
    val end = System.currentTimeMillis() + deadline.toMillis
    def loop: Task[Option[ToolInvoke]] =
      invokeFor(convId, toolName).flatMap {
        case s @ Some(ti) if ti.state == EventState.Complete => Task.pure(s)
        case s if System.currentTimeMillis() > end => Task.pure(s)
        case _ => Task.sleep(50.millis).flatMap(_ => loop)
      }
    loop
  }

  "Stop against an in-flight COOPERATIVE tool" should {

    "cancel the remaining work at the next checkpoint and settle the invoke visibly" in {
      val tool = SlowCooperativeTool
      for {
        convId <- startTurn(tool)
        _ <- Task(tool.midwayLatch.await(10, TimeUnit.SECONDS))
        _ <- publishStop(convId, force = false)
        _ <- Task(tool.proceedLatch.countDown())
        invoke <- awaitInvokeSettled(convId, tool.name)
      } yield {
        // The checkpoint fired: steps 3-6 never ran.
        tool.stepsRun.get() shouldBe 2
        withClue(s"invoke=$invoke: ") {
          invoke should not be empty
          invoke.get.state shouldBe EventState.Complete
        }
      }
    }
  }

  "Stop against an in-flight STUBBORN tool" should {

    "settle the invoke even though the tool ignores the stop and runs to completion" in {
      val tool = SlowStubbornTool
      for {
        convId <- startTurn(tool)
        _ <- Task(tool.midwayLatch.await(10, TimeUnit.SECONDS))
        _ <- publishStop(convId, force = true)
        _ <- Task(tool.proceedLatch.countDown())
        invoke <- awaitInvokeSettled(convId, tool.name)
        _ <- Task.sleep(300.millis) // let any late tool completion land
      } yield withClue(s"invoke=$invoke stepsRun=${tool.stepsRun.get()}: ") {
        invoke should not be empty
        // The one invariant no stop route may break: never a
        // permanently-Active invoke.
        invoke.get.state shouldBe EventState.Complete
      }
    }
  }

  "An un-stopped turn" should {

    "settle the slow tool exactly as before" in {
      val tool = SlowCooperativeTool
      for {
        convId <- startTurn(tool)
        _ <- Task(tool.midwayLatch.await(10, TimeUnit.SECONDS))
        _ <- Task(tool.proceedLatch.countDown()) // no Stop — just proceed
        _ <- TestSigil.awaitSettled(convId)
        invoke <- invokeFor(convId, tool.name)
      } yield {
        tool.stepsRun.get() shouldBe 6
        withClue(s"invoke=$invoke: ") {
          invoke should not be empty
          invoke.get.state shouldBe EventState.Complete
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
