package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.{Conversation, TopicEntry}
import sigil.db.Model
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{ConversationMode, GenerationSettings, Instructions}
import sigil.workflow.{JobStepInput, WorkflowTemplate}

/**
 * Sigil #376 — a workflow run must carry its OWN openable sub-conversation
 * (parented to the scheduling conversation), mirroring delegate_task's worker
 * conv, so the activity pill can open it and the run's activity is inspectable.
 */
class WorkflowSubConversationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  private def boundConv(id: Id[Conversation]): Conversation =
    Conversation(
      topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
      participants = List(DefaultAgentParticipant(
        id = WorkflowTestUser.asInstanceOf[AgentParticipantId],
        modelId = Model.id("test", "model"),
        toolNames = Nil,
        instructions = Instructions(),
        generationSettings = GenerationSettings()
      )),
      currentMode = ConversationMode,
      space = GlobalSpace,
      _id = id
    )

  "WorkflowScheduler (sigil #376)" should {

    "schedule a bound run against its own sub-conversation parented to the scheduling conversation" in {
      val boundId = Conversation.id("sub-conv-bound-1")
      val template = WorkflowTemplate(
        name = "sub-conv-run",
        description = Some("Single noop step."),
        steps = List(JobStepInput(id = "noop", name = Some("Noop step"))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(boundId)
      )
      for {
        _   <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(boundConv(boundId))))
        _   <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        runConvId = run.conversationId.map(Id[Conversation](_))
        subConv <- runConvId match {
          case Some(cid) => TestWorkflowSigil.withDB(_.conversations.transaction(_.get(cid)))
          case None      => Task.pure(None)
        }
      } yield {
        // The run carries a distinct conversation id, NOT the bound one.
        runConvId should not be empty
        runConvId.map(_.value) should not contain boundId.value
        // …and that conversation is parented to the scheduling conversation,
        // so the worker-pill click-to-open resolves the same way it does for a
        // delegate_task worker.
        subConv.flatMap(_.parentConversationId) shouldBe Some(boundId)
      }
    }

    "leave conversationId unset for an unbound (no scheduling conversation) run" in {
      val template = WorkflowTemplate(
        name = "unbound-run",
        description = Some("Single noop step, no bound conversation."),
        steps = List(JobStepInput(id = "noop", name = Some("Noop step"))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = None
      )
      for {
        _   <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
      } yield {
        run.conversationId shouldBe None
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
