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
import sigil.event.ToolInvoke
import sigil.workflow.{JobStepInput, WorkflowTemplate}

import scala.concurrent.duration.*

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
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(boundConv(boundId))))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        runConvId = run.conversationId.map(Id[Conversation](_))
        subConv <- runConvId match {
          case Some(cid) => TestWorkflowSigil.withDB(_.conversations.transaction(_.get(cid)))
          case None => Task.pure(None)
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

    "record a tool step's call into the run's sub-conversation (sigil #376)" in {
      val boundId = Conversation.id("sub-conv-tool-1")
      val template = WorkflowTemplate(
        name = "tool-transcript-run",
        description = Some("Single echo_back tool step."),
        steps = List(JobStepInput(
          id = "echo",
          name = Some("echo step"),
          tool = Some("echo_back"),
          arguments = Some("""{"text":"hi"}"""))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(boundId)
      )
      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(boundConv(boundId))))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        runConvId = Id[Conversation](run.conversationId.getOrElse(fail("run had no sub-conversation")))
        invokes <- waitForToolInvoke(runConvId, "echo_back", 10.seconds)
      } yield {
        // The step's tool call was published into the run's own sub-conversation,
        // so opening it shows the work — not just lifecycle step names.
        invokes should not be empty
        invokes.head.toolName.value shouldBe "echo_back"
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
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
      } yield run.conversationId shouldBe None
    }
  }

  private def waitForToolInvoke(convId: Id[Conversation], toolName: String, timeout: FiniteDuration): Task[List[ToolInvoke]] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[List[ToolInvoke]] =
      TestWorkflowSigil.withDB(_.events.transaction(_.list)).flatMap { events =>
        val invokes = events.collect {
          case ti: ToolInvoke if ti.conversationId == convId && ti.toolName.value == toolName => ti
        }
        if (invokes.nonEmpty || System.currentTimeMillis() > deadline) Task.pure(invokes)
        else Task.sleep(100.millis).flatMap(_ => loop)
      }
    loop
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
