package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Task}
import lightdb.id.Id
import sigil.{ActiveFrameworkWorkflow, CancellationException, TurnContext}
import sigil.conversation.{Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.signal.{FrameworkWorkflowNotice, FrameworkWorkflowPhase}
import sigil.tool.core.{CancelFrameworkWorkflowInput, CancelFrameworkWorkflowOutput, CancelFrameworkWorkflowTool}

import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #51 — framework-workflow cancellation.
 *
 * Verifies:
 *   1. `runAsFrameworkWorkflow` registers / deregisters in the
 *      JVM-wide `activeFrameworkWorkflows` map.
 *   2. `cancelFrameworkWorkflow(id, reason)` flips the token; body
 *      polls and aborts at next checkpoint.
 *   3. The Failed Notice carries the cancellation reason.
 *   4. The `cancel_framework_workflow` tool returns
 *      `Cancelled | NotActive | AlreadyCancelled` correctly.
 */
class FrameworkWorkflowCancellationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def makeContext(): TurnContext = {
    val convId = Id[Conversation](java.util.UUID.randomUUID().toString)
    val topic  = TopicEntry(id = Topic.id(s"topic-$convId"), label = "test", summary = "test")
    val conv   = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      turnInput = TurnInput(ConversationView(conversationId = convId))
    )
  }

  "Sigil.activeFrameworkWorkflows" should {

    "register a workflow on Start and deregister on Completed" in {
      val before = TestSigil.activeFrameworkWorkflows.size
      val started = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val fiber = TestSigil.runAsFrameworkWorkflow("test-register", "registered run", None) { control =>
        Task {
          started.countDown()
          release.await()
          ()
        }
      }.start()
      Task {
        started.await(2, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        TestSigil.activeFrameworkWorkflows.size shouldBe (before + 1)
        TestSigil.activeFrameworkWorkflows.exists(_.workflowType == "test-register") shouldBe true
        release.countDown()
      }.flatMap(_ => fiber.join).map { _ =>
        TestSigil.activeFrameworkWorkflows.size shouldBe before
      }
    }

    "deregister even when the body throws" in {
      val before = TestSigil.activeFrameworkWorkflows.size
      TestSigil.runAsFrameworkWorkflow("test-error-cleanup", "boom", None,
        Task.error[Unit](new RuntimeException("boom"))
      ).handleError(_ => Task.unit).map { _ =>
        TestSigil.activeFrameworkWorkflows.size shouldBe before
      }
    }
  }

  "cancelFrameworkWorkflow" should {

    "raise CancellationException at the next checkpoint inside the body" in {
      val started = new java.util.concurrent.CountDownLatch(1)
      val workflowIdRef = new java.util.concurrent.atomic.AtomicReference[String]("")
      val collected = new java.util.concurrent.ConcurrentLinkedQueue[FrameworkWorkflowPhase]()
      val streamFiber = TestSigil.signals.evalMap {
        case n: FrameworkWorkflowNotice if n.workflowType == "test-cancel" =>
          Task { collected.add(n.phase); () }
        case _ => Task.unit
      }.drain.start()
      val bodyFiber = TestSigil.runAsFrameworkWorkflow("test-cancel", "cancellable run", None) { control =>
        Task {
          workflowIdRef.set(control.token.workflowId)
          started.countDown()
        }.flatMap { _ =>
          Task.sleep(200.millis).flatMap(_ => control.step("about to do work"))
        }.map(_ => "should-not-reach")
      }.handleError {
        case _: CancellationException => Task.pure("cancelled-as-expected")
        case e                        => Task.error(e)
      }.start()
      Task {
        started.await(2, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        TestSigil.cancelFrameworkWorkflow(workflowIdRef.get(), "user-clicked")
      }.flatMap(_ => bodyFiber.join).flatMap { result =>
        result shouldBe "cancelled-as-expected"
        Task.sleep(50.millis).map { _ =>
          streamFiber.cancel()
          val phases = {
            val it = collected.iterator()
            val buf = scala.collection.mutable.ListBuffer.empty[FrameworkWorkflowPhase]
            while (it.hasNext) buf += it.next()
            buf.toList
          }
          phases.head shouldBe a[FrameworkWorkflowPhase.Started]
          phases.last shouldBe a[FrameworkWorkflowPhase.Failed]
          val failed = phases.last.asInstanceOf[FrameworkWorkflowPhase.Failed]
          failed.reason should include("cancelled")
          failed.reason should include("user-clicked")
        }
      }
    }
  }

  "cancel_framework_workflow tool" should {

    "return NotActive for an unknown workflow id" in {
      val tool  = CancelFrameworkWorkflowTool
      val input = CancelFrameworkWorkflowInput(workflowId = "no-such-id")
      val ctx   = makeContext()
      tool.invoke(input, ctx).map { out =>
        out shouldBe CancelFrameworkWorkflowOutput.NotActive("no-such-id")
      }
    }

    "return Cancelled for an active workflow + AlreadyCancelled on second call" in {
      val started = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val workflowIdRef = new java.util.concurrent.atomic.AtomicReference[String]("")
      val bodyFiber = TestSigil.runAsFrameworkWorkflow("test-tool-cancel", "tool-cancellable", None) { control =>
        Task {
          workflowIdRef.set(control.token.workflowId)
          started.countDown()
          release.await()
          ()
        }
      }.start()
      Task {
        started.await(2, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      }.flatMap { _ =>
        val ctx = makeContext()
        val first = CancelFrameworkWorkflowTool.invoke(
          CancelFrameworkWorkflowInput(workflowId = workflowIdRef.get(), reason = Some("first")), ctx
        )
        val second = CancelFrameworkWorkflowTool.invoke(
          CancelFrameworkWorkflowInput(workflowId = workflowIdRef.get(), reason = Some("second")), ctx
        )
        for {
          a <- first
          b <- second
        } yield (a, b)
      }.flatMap { case (firstResult, secondResult) =>
        Task { release.countDown() }.flatMap(_ => bodyFiber.join).map { _ =>
          firstResult shouldBe a[CancelFrameworkWorkflowOutput.Cancelled]
          firstResult.asInstanceOf[CancelFrameworkWorkflowOutput.Cancelled].workflowType shouldBe "test-tool-cancel"
          secondResult shouldBe a[CancelFrameworkWorkflowOutput.AlreadyCancelled]
          secondResult.asInstanceOf[CancelFrameworkWorkflowOutput.AlreadyCancelled].existingReason shouldBe "first"
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
