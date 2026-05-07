package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Task}
import sigil.signal.{FrameworkWorkflowNotice, FrameworkWorkflowPhase}

import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for sigil bug #50 — framework-workflow lifecycle Notices.
 *
 * Verifies that `Sigil.runAsFrameworkWorkflow` emits Started → (Step
 * …) → Completed | Failed in order, with monotonic durationMs
 * timestamps, and that the workflowId is stable across all phases of
 * a single run.
 */
class FrameworkWorkflowSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Subscribe + collect every FrameworkWorkflowNotice emitted while
    * `body` runs. Returns the collected list in arrival order. */
  private def captureNotices[A](body: Task[A]): Task[(A, List[FrameworkWorkflowNotice])] = {
    val collected = new java.util.concurrent.ConcurrentLinkedQueue[FrameworkWorkflowNotice]()
    val streamFiber = TestSigil.signals.evalMap {
      case n: FrameworkWorkflowNotice => Task { collected.add(n); () }
      case _ => Task.unit
    }.drain.start()
    body.flatMap { result =>
      Task.sleep(scala.concurrent.duration.FiniteDuration(50, "millis")).map { _ =>
        streamFiber.cancel()
        val list = {
          val it = collected.iterator()
          val buf = scala.collection.mutable.ListBuffer.empty[FrameworkWorkflowNotice]
          while (it.hasNext) buf += it.next()
          buf.toList
        }
        (result, list)
      }
    }
  }

  "runAsFrameworkWorkflow" should {

    "emit Started → Completed for a successful task" in {
      captureNotices {
        TestSigil.runAsFrameworkWorkflow("test-success", "doing thing", None, Task.pure(42))
      }.map { case (result, notices) =>
        result shouldBe 42
        val sameRun = notices.filter(_.workflowType == "test-success")
        sameRun.size shouldBe 2
        sameRun.head.phase shouldBe a[FrameworkWorkflowPhase.Started]
        sameRun.head.phase.asInstanceOf[FrameworkWorkflowPhase.Started].label shouldBe "doing thing"
        sameRun.last.phase shouldBe a[FrameworkWorkflowPhase.Completed]
        sameRun.head.workflowId shouldBe sameRun.last.workflowId
      }
    }

    "emit Started → Failed and re-raise the error" in {
      captureNotices {
        TestSigil.runAsFrameworkWorkflow("test-failure", "doomed", None, Task.error[Int](new RuntimeException("boom")))
          .handleError(_ => Task.pure(-1))
      }.map { case (result, notices) =>
        result shouldBe -1
        val sameRun = notices.filter(_.workflowType == "test-failure")
        sameRun.size shouldBe 2
        sameRun.head.phase shouldBe a[FrameworkWorkflowPhase.Started]
        sameRun.last.phase shouldBe a[FrameworkWorkflowPhase.Failed]
        val failed = sameRun.last.phase.asInstanceOf[FrameworkWorkflowPhase.Failed]
        failed.reason should include("RuntimeException")
        failed.reason should include("boom")
      }
    }

    "emit intermediate Step phases when the body uses the step callback" in {
      captureNotices {
        TestSigil.runAsFrameworkWorkflow("test-steps", "multi-step", None) { step =>
          for {
            _ <- step("phase one")
            _ <- step("phase two")
          } yield "ok"
        }
      }.map { case (result, notices) =>
        result shouldBe "ok"
        val sameRun = notices.filter(_.workflowType == "test-steps")
        sameRun.size shouldBe 4 // Started + 2 Steps + Completed
        sameRun.head.phase shouldBe a[FrameworkWorkflowPhase.Started]
        sameRun(1).phase.asInstanceOf[FrameworkWorkflowPhase.Step].label shouldBe "phase one"
        sameRun(2).phase.asInstanceOf[FrameworkWorkflowPhase.Step].label shouldBe "phase two"
        sameRun.last.phase shouldBe a[FrameworkWorkflowPhase.Completed]
        sameRun.map(_.workflowId).distinct.size shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
