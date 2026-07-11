package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, TurnContext}
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.tool.{ToolContext, ToolName}
import sigil.workflow.{JobStepInput, WorkflowTemplate}
import sigil.workflow.tool.{GetWorkflowInput, GetWorkflowOutput, GetWorkflowTool}

import scala.concurrent.duration.*

/**
 * `get_workflow` must internally retry instead of failing fast when the
 * template isn't visible yet.
 *
 * In the live runaway the agent created a workflow and immediately fetched it;
 * the result frequently didn't reach the agent, so it retried — across full
 * LLM turns — until the iteration cap. A read that returns `NotFound` the
 * instant the template isn't yet visible turns a brief consistency window into
 * a multi-turn agent loop. Internal sleep/retry absorbs that window inside the
 * single tool call.
 *
 * This inserts the template a few hundred ms AFTER the call begins and asserts
 * `get_workflow` still resolves it to `Found`.
 */
class GetWorkflowRetrySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  override protected val testTimeout: FiniteDuration = 30.seconds

  "GetWorkflowTool" should {
    "resolve a template that becomes visible shortly after the call, not fail fast with NotFound" in {
      val convId = Conversation.id(s"gw-retry-${rapid.Unique()}")
      val ctx = TurnContext(
        sigil = TestWorkflowSigil,
        chain = List(WorkflowTestUser),
        conversation = Conversation(
          topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
          _id = convId
        ),
        turnInput = TurnInput(ConversationView(conversationId = convId)),
        model = TestSigil.defaultTestModel
      )
      val wfId = s"retry-target-${rapid.Unique()}"
      val template = WorkflowTemplate(
        name = "retry-target",
        description = Some("appears late"),
        steps = List(JobStepInput(id = "s", name = Some("step"))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        _id = WorkflowTemplate.id(wfId)
      )

      // The template lands ~300ms into the call — a stand-in for the
      // not-yet-committed window after create_workflow.
      Task.sleep(300.millis)
        .flatMap(_ => TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template))))
        .startUnit()

      new GetWorkflowTool()
        .invoke(GetWorkflowInput(wfId), ToolContext(ctx, Event.id(), ToolName("get_workflow")))
        .map { out =>
          out shouldBe a[GetWorkflowOutput.Found]
          out.asInstanceOf[GetWorkflowOutput.Found].name shouldBe "retry-target"
        }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
