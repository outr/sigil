package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.BeforeAndAfterAll
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, MessageVisibility, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseContent, RespondContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #133 — when the progress checkpoint trips
 * on a stall (`StallDetector` detected an identical-call streak,
 * empty-payload streak, or low-information streak), the framework
 * used to publish a Standard-role directive then immediately release
 * the agent's claim. The agent never got to act on the guidance.
 *
 * Post-fix the stall case publishes Tool-role + `MessageVisibility.Agents`
 * under a synthetic `_stall_detected` parent invoke, then runs ONE
 * forced-synthesis iteration so the agent settles via `respond`.
 * Same shape as #125's cap-hit.
 */
class StallInterventionForcesSynthesisSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  // Checkpoint every 2 iterations — fast loop into the stall path.
  TestSigil.setProgressCheckpointInterval(2)

  override protected def afterAll(): Unit = {
    TestSigil.resetProgressCheckpointInterval()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "stall-soft-stop")

  /** Always emits the same `grep` call (identical input + empty
    * output) so `StallDetector.identicalStreak` fires after a few
    * iterations. On the forced-synthesis turn (signalled by
    * `tool_choice = Specific(respond)`), emits respond instead so
    * the soft-stop completes cleanly. */
  private final class StallThenSynthesizeProvider extends Provider {
    val callCount = new atomic.AtomicInteger(0)
    val toolChoices: atomic.AtomicReference[Vector[ToolChoice]] =
      new atomic.AtomicReference(Vector.empty)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      callCount.incrementAndGet()
      toolChoices.updateAndGet(prev => prev :+ input.toolChoice)
      val callId = CallId(s"call-${rapid.Unique()}")
      // Discriminate by tool roster:
      //   - Single-tool roster containing `report_progress` → the
      //     checkpoint reflector. Emit a ProgressReflectionInput with
      //     `meaningfulProgress = false` and `stuckOn` so the
      //     framework's intervention path fires.
      //   - tool_choice Specific(respond) → forced-synthesis turn.
      //     Emit the synthesised respond.
      //   - Otherwise → the agent's main loop; emit a non-terminal
      //     `change_mode` so the loop iterates AND StallDetector
      //     sees an identical-call streak.
      val isReflectorCall = input.tools.exists(_.name.value == "report_progress")
      val emits: List[ProviderEvent] =
        if (isReflectorCall) {
          List(
            ProviderEvent.ToolCallStart(callId, "report_progress"),
            ProviderEvent.ToolCallComplete(
              callId,
              _root_.sigil.tool.consult.ProgressReflectionInput(
                currentStatus      = "still looping on change_mode",
                meaningfulProgress = false,
                remainingSteps     = "wrap up and respond",
                stuckOn            = Some("looping"),
                shouldAskUser      = false
              )
            ),
            ProviderEvent.Done(StopReason.Complete)
          )
        } else input.toolChoice match {
          case ToolChoice.Specific(name) if name == RespondTool.schema.name =>
            List(
              ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
              ProviderEvent.ToolCallComplete(
                callId,
                RespondInput(
                  topicLabel   = "Stall-synth",
                  topicSummary = "forced-synthesis after stall",
                  content      = RespondContent.Text("Synthesised from gathered context after stall intercept."),
                  endsTurn     = true
                )
              ),
              ProviderEvent.Done(StopReason.Complete)
            )
          case _ =>
            List(
              ProviderEvent.ToolCallStart(callId, ChangeModeTool.schema.name.value),
              ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "conversation")),
              ProviderEvent.Done(StopReason.ToolCall)
            )
        }
      Stream.emits(emits)
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  "Stall-detected intervention" should {

    "publish a `_stall_detected` synthetic ToolInvoke and run one forced-synthesis iteration" in {
      val provider = new StallThenSynthesizeProvider
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"stall-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = TestTopicEntry.id,
                 content        = Vector(ResponseContent.Text("Evaluate the X system")),
                 state          = EventState.Complete
               ))
        _   <- Task.sleep(6.seconds) // generous window for the loop + checkpoint + forced-synth
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val convEvs = evs.filter(_.conversationId == convId)

        // The synthetic `_stall_detected` ToolInvoke must land.
        val stallInvokes = convEvs.collect {
          case ti: ToolInvoke if ti.toolName.value == "_stall_detected" => ti
        }
        withClue(s"events: ${convEvs.map(_.getClass.getSimpleName).mkString(", ")}: ") {
          stallInvokes should not be empty
        }

        // The directive paired to it must be Tool-role + Agents
        // visibility (user doesn't see it raw).
        val syntheticId = stallInvokes.head._id
        val directives = convEvs.collect {
          case m: Message if m.role == MessageRole.Tool && m.origin.contains(syntheticId) => m
        }
        directives should not be empty
        directives.head.visibility shouldBe MessageVisibility.Agents

        // The provider got a Specific(respond) pin at some point —
        // proves the forced-synthesis turn ran.
        val forcedChoices = provider.toolChoices.get().collect {
          case s: ToolChoice.Specific => s.toolName.value
        }
        forcedChoices should contain (RespondTool.schema.name.value)

        // Forced-synthesis emitted a Standard-role respond from the agent.
        val agentReplies = convEvs.collect {
          case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard => m
        }
        agentReplies should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
