package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, SkillSource, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message}
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant, WorkerParticipantId}
import sigil.provider.{AnalysisWork, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.role.Role
import sigil.tool.{ToolContext, ToolName, ToolResult}
import sigil.tool.model.{DelegateTaskInput, ResponseContent}
import sigil.tool.util.{DelegateTaskOutput, DelegateTaskTool}

/**
 * #327 — `delegate_task` is the supervised agent-bridge: it must create a
 * real two-agent sub-conversation `[supervisor, worker]` linked to the
 * parent, activate the [[sigil.tool.util.WorkerSupervisorSkill]] on the
 * supervisor's projection there, and post the brief addressed to the
 * worker (which fires the worker's first canonical turn).
 *
 * Deterministic structural coverage — the worker agent's actual turn
 * execution is the canonical agent-loop path (exercised by the live
 * conversation specs); the live two-agent exchange terminates by the
 * supervisor relaying up and not re-addressing the worker
 * ([[WorkerConversationAddressingSpec]]) with the per-conversation turn
 * budget as a backstop.
 */
class DelegationBridgeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)
  TestWorkflowSigil.cache.merge(TestSigil.knownTestModels).sync()
  // The brief fires the worker; a silent provider (no tool call) lets it
  // rest cleanly (chat-fidelity) instead of failing for lack of a wired
  // provider — keeps the structural assertions free of background noise.
  private final class SilentProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestWorkflowSigil
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): rapid.Stream[ProviderEvent] =
      rapid.Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
  }
  TestWorkflowSigil.setProvider(Task.pure(new SilentProvider))

  private val supervisorId: AgentParticipantId = WorkflowTestUser.asInstanceOf[AgentParticipantId]
  private val modelId = Model.id("test", "model")

  "delegate_task (agent-bridge)" should {
    "create a [supervisor, worker] sub-conversation, activate the supervisor skill, and address the brief to the worker" in {
      val supervisor = DefaultAgentParticipant(id = supervisorId, modelId = modelId)
      for {
        parent <- TestWorkflowSigil.newConversation(
          createdBy = supervisorId, label = "bridge-parent", participants = List(supervisor)
        )
        ctx = ToolContext(
          TurnContext(
            sigil        = TestWorkflowSigil,
            chain        = List(supervisorId),
            conversation = parent,
            turnInput    = TurnInput(ConversationView(conversationId = parent._id)),
            model        = TestWorkflowSigil.cache.find(modelId).get
          ),
          Event.id(),
          ToolName("delegate_task")
        )
        result <- DelegateTaskTool.executeResult(
          DelegateTaskInput(
            role    = "calculator",
            roleDescription = Some("Compute simple arithmetic."),
            brief   = "Compute 2 + 2 and report the result.",
            modelId = Some(modelId.value)
          ),
          ctx
        )
        output = result match {
          case ToolResult.Success(o: DelegateTaskOutput) => o
          case other                                     => fail(s"expected DelegateTaskOutput success, got $other")
        }
        workerConvId = Conversation.id(output.workerConvId)
        workerConv <- TestWorkflowSigil.withDB(_.conversations.transaction(_.get(workerConvId))).map(_.get)
        proj       <- TestWorkflowSigil.projectionFor(supervisorId, workerConvId)
        events     <- TestWorkflowSigil.withDB(_.eventsTransaction(workerConvId)(_.list))
          .map(_.filter(_.conversationId == workerConvId))
        // The brief fires the worker on a background fiber; the silent
        // provider lets it rest immediately. Let that settle before the
        // suite tears the DB down so the in-flight fiber doesn't race dispose.
        _ <- rapid.Task.sleep(scala.concurrent.duration.DurationInt(500).millis)
      } yield {
        // Two real agents in the sub-conversation, linked to the parent.
        workerConv.parentConversationId shouldBe Some(parent._id)
        workerConv.participants.map(_.id) should contain (supervisorId)
        workerConv.participants.exists(_.id.isInstanceOf[WorkerParticipantId]) shouldBe true
        // Supervisor bridge skill active on the supervisor's projection there.
        proj.activeSkills.get(SkillSource.Supervisor).map(_.name) shouldBe Some("worker_supervisor")
        // The brief is posted addressed to the worker (so it — and only it
        // — wakes), authored by the supervisor.
        val brief = events.collectFirst {
          case m: Message if m.content.collect { case ResponseContent.Text(t) => t }.mkString.contains("2 + 2") => m
        }
        withClue(s"brief events: ${events.map(_.getClass.getSimpleName)}: ") {
          brief should not be empty
          brief.get.participantId shouldBe supervisorId
          brief.get.addressees.exists(_.exists(_.isInstanceOf[WorkerParticipantId])) shouldBe true
        }
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
