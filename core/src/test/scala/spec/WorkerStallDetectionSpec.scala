package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, MessageVisibility, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant, ParticipantId, WorkerParticipantId}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*

/**
 * Sigil #332 — #330 over-corrected. It fixed a real misfire (the LLM
 * self-assessment asking the user for clarification inside a worker, which
 * the supervisor should own) by suppressing the *entire* progress
 * checkpoint in worker sub-conversations — which also disabled the
 * mechanical stall detector. A grinding worker then flailed unchecked to
 * the iteration cap.
 *
 * The fix runs the checkpoint in workers too, but redirects an
 * `askingUser` intervention to a supervisor handoff (a Tool-role directive
 * + one forced-synthesis `respond`) instead of a user-facing
 * clarification. This locks both halves: the stall detector breaks a
 * worker loop early, and an ask-user signal never surfaces a user-facing
 * clarification from within a worker.
 */
class WorkerStallDetectionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProgressCheckpointInterval(2)

  override protected def afterAll(): Unit = {
    TestSigil.resetProgressCheckpointInterval()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "worker-stall")
  TestSigil.testModel(modelId)

  private val workerId = WorkerParticipantId(s"worker-${rapid.Unique()}")

  /** Stub provider:
    *   - reflector call (roster has `report_progress`) → emit a
    *     ProgressReflection with `meaningfulProgress = false` and the
    *     supplied `shouldAskUser`.
    *   - worker, forced-synthesis (`tool_choice = Specific(respond)`) →
    *     emit the worker's `respond` handoff.
    *   - worker, otherwise → emit an identical `change_mode` so the loop
    *     iterates and the stall detector sees a repeated-call streak.
    *   - supervisor → respond immediately (endsTurn) so it never loops. */
  private final class WorkerLoopProvider(askUser: Boolean) extends Provider {
    val stallInvokeSeen = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"call-${rapid.Unique()}")
      val isReflector = input.tools.exists(_.name.value == "report_progress")
      val isWorker    = input.agentId.contains(workerId)
      val emits: List[ProviderEvent] =
        if (isReflector)
          List(
            ProviderEvent.ToolCallStart(callId, "report_progress"),
            ProviderEvent.ToolCallComplete(
              callId,
              _root_.sigil.tool.consult.ProgressReflectionInput(
                currentStatus      = "still looping on change_mode",
                meaningfulProgress = false,
                remainingSteps     = "wrap up",
                stuckOn            = Some("looping"),
                shouldAskUser      = askUser
              )
            ),
            ProviderEvent.Done(StopReason.Complete)
          )
        else if (!isWorker || input.toolChoice == ToolChoice.Specific(RespondTool.schema.name))
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(
              callId,
              RespondInput(
                topicLabel   = "Handoff",
                topicSummary = "report after stall",
                content      = "Reporting what I found and where I'm blocked.",
                endsTurn     = true
              )
            ),
            ProviderEvent.Done(StopReason.Complete)
          )
        else
          List(
            ProviderEvent.ToolCallStart(callId, ChangeModeTool.schema.name.value),
            ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "coding")),
            ProviderEvent.Done(StopReason.ToolCall)
          )
      Stream.emits(emits)
    }
  }

  private def supervisor: AgentParticipant =
    DefaultAgentParticipant(id = TestAgent, modelId = modelId,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)))

  private def worker: AgentParticipant =
    DefaultAgentParticipant(id = workerId, modelId = modelId,
      toolNames = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)))

  /** A directed worker sub-conversation [supervisor, worker] linked to a
    * parent, with the worker primed by a brief addressed to it alone (so
    * only the worker fires on the trigger). */
  private def runWorkerLoop(askUser: Boolean): Task[List[sigil.event.Event]] = {
    val provider = new WorkerLoopProvider(askUser)
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"worker-stall-${rapid.Unique()}")
    val conv = Conversation(
      topics               = TestTopicStack,
      participants         = List(supervisor, worker),
      parentConversationId = Some(Conversation.id(s"parent-${rapid.Unique()}")),
      _id                  = convId
    )
    for {
      _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _   <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Find all bug references in the repo.")),
               addressees     = Some(Set[ParticipantId](workerId)),
               state          = EventState.Complete
             ))
      _   <- Task.sleep(7.seconds)
      evs <- TestSigil.withDB(_.events.transaction(_.list))
    } yield evs.filter(_.conversationId == convId)
  }

  "the progress checkpoint in a worker sub-conversation" should {
    "run the stall detector and break a worker's degenerate loop early (not flail to the cap)" in {
      runWorkerLoop(askUser = false).map { convEvs =>
        // The checkpoint ran in the worker — the synthetic `_stall_detected`
        // invoke landed (it would be absent under #330's full suppression).
        val stallInvokes = convEvs.collect {
          case ti: ToolInvoke if ti.toolName.value == "_stall_detected" && ti.participantId == workerId => ti
        }
        withClue(s"events: ${convEvs.map(_.getClass.getSimpleName).mkString(", ")}: ") {
          stallInvokes should not be empty
        }
        // The worker synthesised a respond (loop broken), and never
        // approached the iteration cap.
        val workerReplies = convEvs.collect {
          case m: Message if m.participantId == workerId && m.role == MessageRole.Standard => m
        }
        workerReplies should not be empty
        val changeModes = convEvs.collect {
          case ti: ToolInvoke if ti.toolName.value == "change_mode" && ti.participantId == workerId => ti
        }
        changeModes.size should be < TestSigil.maxAgentIterations
      }
    }

    "redirect an ask-user checkpoint to a supervisor handoff, not a user-facing clarification" in {
      runWorkerLoop(askUser = true).map { convEvs =>
        // No user-facing orchestrator-intervention message escapes from
        // the worker — the supervisor owns asking the human.
        val userFacing = convEvs.collect {
          case m: Message if m.source.contains("orchestrator-intervention") => m
        }
        userFacing shouldBe empty

        // Instead: a Tool-role `_stall_detected` directive that tells the
        // worker to report to its supervisor, plus the forced-synthesis
        // respond.
        val stallInvokes = convEvs.collect {
          case ti: ToolInvoke if ti.toolName.value == "_stall_detected" && ti.participantId == workerId => ti
        }
        stallInvokes should not be empty
        val directive = convEvs.collect {
          case m: Message if m.role == MessageRole.Tool && m.origin.contains(stallInvokes.head._id) => m
        }
        directive should not be empty
        directive.head.visibility shouldBe MessageVisibility.Agents
        val directiveText = directive.head.content.collect { case ResponseContent.Text(t) => t }.mkString
        directiveText.toLowerCase should include("supervisor")

        val workerReplies = convEvs.collect {
          case m: Message if m.participantId == workerId && m.role == MessageRole.Standard => m
        }
        workerReplies should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
