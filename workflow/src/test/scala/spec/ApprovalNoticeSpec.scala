package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.workflow.signal.WorkflowApprovalRequested
import sigil.workflow.{ApprovalStepInput, SigilApproval, WorkflowHost}
import strider.Workflow

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Verifies [[SigilApproval.onWaiting]] publishes a
 * [[WorkflowApprovalRequested]] Notice into the originating
 * conversation when the workflow run carries a `conversationId`,
 * and is silent for cron-fired runs without one.
 *
 * Reuses [[TestWorkflowSigil]] (initialized by
 * [[WorkflowEndToEndSpec]] for its DB path) — `WorkflowHost` is
 * already pointed at it because `WorkflowSigil`'s trait body sets
 * the static reference at first instantiation.
 */
class ApprovalNoticeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestWorkflowSigil.initFor(getClass.getSimpleName)

  "SigilApproval.onWaiting" should {
    "publish a WorkflowApprovalRequested Notice into the conversation when conversationId is set" in {
      val recorded = new ConcurrentLinkedQueue[Any]()
      @volatile var running = true
      val host = WorkflowHost.get
      host.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      val convIdStr = "approval-test-conv"
      val approval = SigilApproval(ApprovalStepInput(
        id = "review",
        name = "Review the change",
        prompt = "Approve or reject the rollout?",
        options = List("approve", "reject"),
        timeoutMs = Some(30000L)
      ))
      val wf = Workflow(
        name = "approval-spec",
        steps = Nil,
        scheduled = 0L,
        queue = Nil,
        sourceId = Id("src"),
        conversationId = Some(convIdStr)
      )

      approval.onWaiting(wf).map { _ =>
        // Drain a beat so the publish + signal hub deliver.
        Thread.sleep(150)
        running = false
        import scala.jdk.CollectionConverters.*
        val notices = recorded.iterator().asScala.toList.collect {
          case n: WorkflowApprovalRequested => n
        }
        notices should have size 1
        val n = notices.head
        n.runId shouldBe wf._id.value
        n.stepId shouldBe approval.id.value
        n.stepName shouldBe "Review the change"
        n.prompt shouldBe "Approve or reject the rollout?"
        n.options shouldBe List("approve", "reject")
        n.timeoutMs shouldBe Some(30000L)
        n.conversationId.value shouldBe convIdStr
      }
    }

    "stay silent when the workflow has no conversationId (cron-style background run)" in {
      val recorded = new ConcurrentLinkedQueue[Any]()
      @volatile var running = true
      val host = WorkflowHost.get
      host.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      val approval = SigilApproval(ApprovalStepInput(
        id = "review",
        prompt = "Cron decision",
        options = List("yes", "no")
      ))
      val wf = Workflow(
        name = "approval-cron",
        steps = Nil,
        scheduled = 0L,
        queue = Nil,
        sourceId = Id("src"),
        conversationId = None
      )

      approval.onWaiting(wf).map { _ =>
        Thread.sleep(150)
        running = false
        import scala.jdk.CollectionConverters.*
        recorded.iterator().asScala.toList.collect {
          case n: WorkflowApprovalRequested => n
        } shouldBe empty
      }
    }
  }
}
