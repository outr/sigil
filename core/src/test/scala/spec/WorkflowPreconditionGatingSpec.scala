package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.{Conversation, TopicEntry}
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunFailed}
import sigil.provider.ConversationMode
import sigil.signal.Signal
import sigil.workflow.{JobStepInput, WorkflowTemplate}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Workflow-step precondition gating — a tool with an unsatisfied
 * precondition dispatched from a workflow step is properly blocked:
 * the resolution never runs and the step's payload surfaces the
 * blocked state. Workflow dispatch used to bypass preconditions
 * entirely; it now runs the same executor gate pipeline as the
 * orchestrator path.
 */
class WorkflowPreconditionGatingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
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

  "a workflow Job step dispatching a precondition-gated tool" should {
    "block the dispatch and surface the unmet precondition instead of running the body" in {
      val convId = Conversation.id(s"gating-${rapid.Unique()}")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      val template = WorkflowTemplate(
        name = "gated-tool",
        description = Some("Single gated_probe step whose precondition is unsatisfied"),
        steps = List(JobStepInput(id = "gated", tool = Some("gated_probe"), output = Some("r"))),
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
        _ <- waitForTerminal(recorded, 10.seconds)
        run <- {
          import scala.jdk.CollectionConverters.*
          val runId = recorded.iterator().asScala.collectFirst {
            case e: WorkflowRunCompleted => e.runId
            case e: WorkflowRunFailed => e.runId
          }.getOrElse(fail("no terminal workflow lifecycle event observed"))
          TestWorkflowSigil.withDB(_.workflows.transaction(_.get(lightdb.id.Id[strider.Workflow](runId))))
        }
      } yield {
        running = false
        GatedProbeTool.ran shouldBe false
        val wf = run.getOrElse(fail("run record not found"))
        val payload = wf.variables.get("r").orElse(wf.payloads.values.headOption)
          .map(fabric.io.JsonFormatter.Compact(_)).getOrElse("")
        payload should include("preconditions not met")
        payload should include("docker daemon")
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
