package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{AgentState, Event, Message, Stop}
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.{InMemoryToolFinder, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Sigil #392 — a force `Stop` must interrupt a tool that's already executing,
 * not wait for it to return. The agent-loop drain is raced against the
 * force-stop flag (built on rapid's `Fiber.cancel`/`Task.race`), so when the
 * flag flips the drain fiber is cancelled mid-tool and the loop unwinds.
 *
 * This drives the real loop: the agent calls a tool that blocks for 30s; a
 * `Stop(force = true)` arrives while it's blocked. Pre-fix the agent settled
 * only after the 30s tool returned (`takeWhile(!force)` fires only at element
 * boundaries); now it settles within a couple of seconds.
 */
class Stop392InterruptToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "stop-392")
  TestSigil.testModel(modelId)

  case class SlowInput() extends ToolInput derives RW
  ToolInput.register(RW.static(SlowInput()))

  /** Atomic tool that blocks for 30s — models a slow image upload / page render
    * mid-execution when the user hits Stop. The interrupt aborts the
    * `Thread.sleep`. */
  private case object SlowBlockingTool extends Tool {
    type Input  = SlowInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[SlowInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName("slow_blocking")
    val description = "Blocks for 30s; used to prove a force Stop interrupts an in-flight tool."
    override def executeResult(input: SlowInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task { Thread.sleep(30000L); ToolResult.Success(TextToolOutput("done")) }
  }

  /** Emits a single call to the slow tool, then Done. */
  private final class SlowToolProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(in: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
      ProviderEvent.ToolCallStart(CallId("slow-1"), SlowBlockingTool.name.value),
      ProviderEvent.toolCall(CallId("slow-1"), SlowBlockingTool)(SlowInput()),
      ProviderEvent.Done(StopReason.ToolCall)
    ))
  }

  private def agent(): DefaultAgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = List(SlowBlockingTool.name),
      instructions       = Instructions(),
      generationSettings = GenerationSettings())

  "A force Stop (sigil #392)" should {
    "interrupt an in-flight tool so the agent settles promptly instead of waiting it out" in {
      TestSigil.setProvider(Task.pure(new SlowToolProvider))
      TestSigil.setToolFinder(InMemoryToolFinder(CoreTools.all.toList :+ SlowBlockingTool))

      val convId = Conversation.id(s"stop392-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(agent()), _id = convId)

      def lockState: Task[Option[EventState]] =
        TestSigil.withDB(_.events.transaction(_.get(Id[Event](s"agentlock:${TestAgent.value}:${convId.value}"))))
          .map(_.collect { case s: AgentState => s.state })

      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _       <- TestSigil.publish(Message(
                     participantId  = TestUser,
                     conversationId = convId,
                     topicId        = TestTopicEntry.id,
                     content        = Vector(ResponseContent.Text("run the slow tool")),
                     state          = EventState.Complete))
        // Let the agent fire and get into the blocking tool.
        _       <- Task.sleep(600.millis)
        // Sanity: the agent is mid-turn (claim Active), not yet settled.
        midState <- lockState
        stopAt   =  System.currentTimeMillis()
        _       <- TestSigil.publish(Stop(
                     participantId  = TestUser,
                     conversationId = convId,
                     topicId        = TestTopicEntry.id,
                     force          = true,
                     state          = EventState.Complete))
        _       <- TestSigil.awaitSettled(convId, TestAgent, timeout = 15.seconds)
        elapsed  =  System.currentTimeMillis() - stopAt
        endState <- lockState
      } yield {
        TestSigil.reset()
        withClue(s"agent should have been mid-turn (Active) before Stop, was $midState: ") {
          midState shouldBe Some(EventState.Active)
        }
        withClue(s"agent should have settled (Complete) after Stop, was $endState: ") {
          endState shouldBe Some(EventState.Complete)
        }
        withClue(s"settle took ${elapsed}ms after Stop — should be a couple seconds, not ~30s: ") {
          elapsed should be < 8000L
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
