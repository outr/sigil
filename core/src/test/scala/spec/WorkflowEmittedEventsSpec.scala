package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.{Conversation, TopicEntry}

import sigil.provider.ConversationMode
import sigil.signal.Signal
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunFailed}
import sigil.workflow.{JobStepInput, WorkflowTemplate}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * A workflow step's tool emits durable events through `ctx.emit`; those
 * events ARE the step's effect and must be published, not dropped on the
 * floor with only the synthesized invoke surviving. Proven end-to-end:
 * a `record_consent` step's `ToolApproval` has to reach the store for
 * the following consent-gated step to dispatch at all.
 */
class WorkflowEmittedEventsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  private def waitForTerminal(recorded: ConcurrentLinkedQueue[Signal], timeout: FiniteDuration): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = Task.defer {
      import scala.jdk.CollectionConverters.*
      val seen = recorded.iterator().asScala.exists {
        case _: WorkflowRunCompleted | _: WorkflowRunFailed => true
        case _ => false
      }
      if (seen || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    }
    loop
  }

  "a workflow Job step whose tool emits durable events" should {
    "publish them, so a later consent-gated step sees the recorded approval" in {
      ConsentProbeTool.ran = false
      val convId = Conversation.id(s"emitted-${rapid.Unique()}")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      val template = WorkflowTemplate(
        name = "consent-then-gated",
        description = Some("record_consent, then dispatch the tool it unlocked"),
        steps = List(
          JobStepInput(
            id = "grant",
            tool = Some("record_consent"),
            arguments = Some("""{"toolName":"consent_probe","approved":true,"reason":"workflow-authorized"}"""),
            output = Some("grant")
          ),
          JobStepInput(id = "gated", tool = Some("consent_probe"), output = Some("gated"))
        ),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(sigil.participant.DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[sigil.participant.AgentParticipantId],
          modelId = TestWorkflowSigil.testModelId
        )),
        currentMode = ConversationMode,
        space = GlobalSpace,
        _id = convId
      )

      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _ <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _ <- waitForTerminal(recorded, 20.seconds)
        run <- {
          import scala.jdk.CollectionConverters.*
          val runId = recorded.iterator().asScala.collectFirst {
            case e: WorkflowRunCompleted => e.runId
            case e: WorkflowRunFailed => e.runId
          }.getOrElse(fail("no terminal workflow lifecycle event observed"))
          TestWorkflowSigil.withDB(_.workflows.transaction(_.get(lightdb.id.Id[strider.Workflow](runId))))
        }
        // The approval lands in the RUN's conversation — the same scope
        // the gated step's consent lookup reads from.
        runConvId = run.flatMap(_.conversationId).getOrElse(fail("run has no bound conversation"))
        approval <- TestWorkflowSigil.latestToolApproval(
          sigil.tool.ToolName("consent_probe"),
          Conversation.id(runConvId))
      } yield {
        running = false
        val wf = run.getOrElse(fail("run record not found"))
        withClue(s"variables=${wf.variables}: ") {
          approval.map(_.toolName.value) shouldBe Some("consent_probe")
          approval.exists(_.approved) shouldBe true
          approval.flatMap(_.reason) shouldBe Some("workflow-authorized")
          val gated = wf.variables.get("gated").map(fabric.io.JsonFormatter.Compact(_)).getOrElse("")
          gated should not include "requires user consent"
          ConsentProbeTool.ran shouldBe true
        }
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
