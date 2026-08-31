package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Stream, Task}
import _root_.sigil.RunUnit
import _root_.sigil.Sigil
import _root_.sigil.conversation.Conversation
import _root_.sigil.db.Model
import _root_.sigil.event.Message
import _root_.sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import _root_.sigil.provider.{GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderType}
import _root_.sigil.signal.{FrameworkWorkflowNotice, FrameworkWorkflowPhase, Signal}
import _root_.sigil.tool.core.CoreTools
import _root_.sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Pins the `RunUnit` lifecycle primitive. Covers:
 *
 *   1. A crashing unit emits `Started` → `Failed` and re-throws.
 *   2. A happy unit emits `Started` → `Completed` and yields the value.
 *   3. `runAsFrameworkWorkflow` (the legacy callsite shape) still
 *      produces the same Notice stream — backwards-compat shim.
 *   4. `RunUnit.execute` does NOT auto-publish a cross-conversation
 *      echo Notice even when `parentConversationId` is set (the
 *      `delegate_task` parent-echo flow stays sourced from
 *      `SigilWorkflowManager`, not `RunUnit`).
 *   5. A `runAgent` top-level wrap surfaces the operational
 *      `Started` Notice when the agent loop enters and a terminal
 *      `Failed` Notice when the loop blows up — closes the
 *      observability gap adjacent to bug #312 (operational
 *      observers couldn't tell an agent loop had terminated when
 *      its body threw mid-stream).
 */
class RunUnitSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private given Sigil = TestSigil

  private def captureNotices[A](body: Task[A]): Task[(A, List[FrameworkWorkflowNotice])] = {
    val collected = new ConcurrentLinkedQueue[FrameworkWorkflowNotice]()
    val streamFiber = TestSigil.signals.evalMap {
      case n: FrameworkWorkflowNotice => Task { collected.add(n); () }
      case _ => Task.unit
    }.drain.start()
    body.flatMap { result =>
      Task.sleep(scala.concurrent.duration.FiniteDuration(75, "millis")).map { _ =>
        streamFiber.cancel()
        (result, collected.iterator().asScala.toList)
      }
    }
  }

  "RunUnit.execute" should {

    "emit Started → Completed and return the produced value for a happy unit" in {
      val unit = new RunUnit[Int] {
        val label = "happy path"
        val workflowType = "run-unit-happy"
        val conversationId = None
        val run = Task.pure(7)
      }
      captureNotices(RunUnit.execute(unit)).map { case (result, notices) =>
        result shouldBe 7
        val mine = notices.filter(_.workflowType == "run-unit-happy")
        mine.size shouldBe 2
        mine.head.phase shouldBe a[FrameworkWorkflowPhase.Started]
        mine.head.phase.asInstanceOf[FrameworkWorkflowPhase.Started].label shouldBe "happy path"
        mine.last.phase shouldBe a[FrameworkWorkflowPhase.Completed]
        mine.head.workflowId shouldBe mine.last.workflowId
      }
    }

    "emit Started → Failed and re-throw when the body errors" in {
      val unit = new RunUnit[Int] {
        val label = "doomed"
        val workflowType = "run-unit-crash"
        val conversationId = None
        val run = Task.error[Int](new RuntimeException("synthetic crash"))
      }
      captureNotices(RunUnit.execute(unit).handleError(_ => Task.pure(-1))).map { case (result, notices) =>
        result shouldBe -1
        val mine = notices.filter(_.workflowType == "run-unit-crash")
        mine.size shouldBe 2
        mine.head.phase shouldBe a[FrameworkWorkflowPhase.Started]
        mine.last.phase shouldBe a[FrameworkWorkflowPhase.Failed]
        val failed = mine.last.phase.asInstanceOf[FrameworkWorkflowPhase.Failed]
        failed.reason should include("RuntimeException")
        failed.reason should include("synthetic crash")
      }
    }

    "fire onCompleted before emitting the Completed Notice" in {
      val fired = new atomic.AtomicBoolean(false)
      val unit = new RunUnit[String] {
        val label = "with hook"
        val workflowType = "run-unit-hook-ok"
        val conversationId = None
        val run = Task.pure("v")
        override def onCompleted(result: String): Task[Unit] = Task { fired.set(true); () }
      }
      captureNotices(RunUnit.execute(unit)).map { case (result, notices) =>
        result shouldBe "v"
        fired.get() shouldBe true
        val mine = notices.filter(_.workflowType == "run-unit-hook-ok")
        mine.last.phase shouldBe a[FrameworkWorkflowPhase.Completed]
      }
    }

    "fire onFailed before emitting the Failed Notice and still re-throw" in {
      val seen = new atomic.AtomicReference[Option[Throwable]](None)
      val unit = new RunUnit[Int] {
        val label = "doomed-hooked"
        val workflowType = "run-unit-hook-fail"
        val conversationId = None
        val run = Task.error[Int](new IllegalStateException("hook-fail"))
        override def onFailed(t: Throwable): Task[Unit] = Task { seen.set(Some(t)); () }
      }
      captureNotices(RunUnit.execute(unit).handleError(_ => Task.pure(0))).map { case (result, _) =>
        result shouldBe 0
        seen.get() match {
          case Some(t) => t.getMessage shouldBe "hook-fail"
          case None => fail("onFailed was not invoked")
        }
      }
    }

    "skip the Completed Notice when emitCompleted = false but still fire Started and Failed" in {
      val unit = new RunUnit[Int] {
        val label = "silent-success"
        val workflowType = "run-unit-no-complete"
        val conversationId = None
        val run = Task.pure(1)
        override def emitCompleted: Boolean = false
      }
      captureNotices(RunUnit.execute(unit)).map { case (result, notices) =>
        result shouldBe 1
        val mine = notices.filter(_.workflowType == "run-unit-no-complete")
        mine.size shouldBe 1
        mine.head.phase shouldBe a[FrameworkWorkflowPhase.Started]
      }
    }

    "NOT publish a parent-conversation echo Notice even when parentConversationId is set" in {
      // Locked-design pin: `RunUnit.execute` carries the parent hint as
      // forward-compat metadata only — it never auto-publishes a
      // cross-conversation echo. (Worker delegation bridges explicitly via
      // relay_message, sigil #327.)
      val parent = Conversation.id("rununit-parent")
      val worker = Conversation.id("rununit-worker")
      val unit = new RunUnit[Int] {
        val label = "worker-shaped"
        val workflowType = "run-unit-worker"
        val conversationId = Some(worker)
        override val parentConversationId: Option[Id[Conversation]] = Some(parent)
        val run = Task.pure(99)
      }
      captureNotices(RunUnit.execute(unit)).map { case (_, notices) =>
        val mine = notices.filter(_.workflowType == "run-unit-worker")
        mine.foreach(_.conversationId shouldBe Some(worker))
        // No emission carries the parent id — that's the locked
        // design pin.
        mine.exists(_.conversationId.contains(parent)) shouldBe false
      }
    }
  }

  "runAsFrameworkWorkflow (backwards-compat shim)" should {

    "emit Started → Completed for the legacy Task overload" in
      captureNotices {
        TestSigil.runAsFrameworkWorkflow("shim-success", "shim happy", None, Task.pure("ok"))
      }.map { case (result, notices) =>
        result shouldBe "ok"
        val mine = notices.filter(_.workflowType == "shim-success")
        mine.size shouldBe 2
        mine.head.phase shouldBe a[FrameworkWorkflowPhase.Started]
        mine.last.phase shouldBe a[FrameworkWorkflowPhase.Completed]
      }

    "emit Started → Failed for the legacy Task overload on error" in
      captureNotices {
        TestSigil.runAsFrameworkWorkflow(
          "shim-failure",
          "shim doomed",
          None,
          Task.error[String](new RuntimeException("shim boom")))
          .handleError(_ => Task.pure("recovered"))
      }.map { case (result, notices) =>
        result shouldBe "recovered"
        val mine = notices.filter(_.workflowType == "shim-failure")
        mine.size shouldBe 2
        mine.last.phase shouldBe a[FrameworkWorkflowPhase.Failed]
        mine.last.phase.asInstanceOf[FrameworkWorkflowPhase.Failed].reason should include("shim boom")
      }

    "still emit Step phases through the control-callback overload" in
      captureNotices {
        TestSigil.runAsFrameworkWorkflow("shim-steps", "shim multi-step", None) { control =>
          for {
            _ <- control.step("a")
            _ <- control.step("b")
          } yield "done"
        }
      }.map { case (result, notices) =>
        result shouldBe "done"
        val mine = notices.filter(_.workflowType == "shim-steps")
        mine.size shouldBe 4
        mine(1).phase.asInstanceOf[FrameworkWorkflowPhase.Step].label shouldBe "a"
        mine(2).phase.asInstanceOf[FrameworkWorkflowPhase.Step].label shouldBe "b"
        mine.head.workflowId shouldBe mine.last.workflowId
      }
  }

  "runAgent (top-level RunUnit wrap)" should {

    "emit an agent-loop Started Notice when the loop enters and a Failed Notice when the loop blows up" in {
      val modelId: Id[Model] = Model.id("test", "run-unit-agent")
      TestSigil.testModel(modelId)

      class CrashingProvider extends Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[Model] = Nil
        override protected def sigil: Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] =
          Stream.force(Task.error(new RuntimeException("synthetic agent-loop crash")))
      }

      val agent: AgentParticipant = DefaultAgentParticipant(
        id = TestAgent,
        modelId = modelId,
        toolNames = CoreTools.coreToolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings(maxOutputTokens = Some(40), temperature = Some(0.0))
      )

      TestSigil.setProvider(Task.pure(new CrashingProvider))

      val convId = Conversation.id(s"run-unit-agent-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

      val recorded = new ConcurrentLinkedQueue[Signal]()
      val running = new atomic.AtomicBoolean(true)
      TestSigil.signals
        .takeWhile(_ => running.get())
        .evalMap(s => Task { recorded.add(s); () })
        .drain
        .startUnit()

      def settled: Boolean = {
        val snapshot = recorded.iterator().asScala.toList
        val agentLoopNotices = snapshot.collect {
          case n: FrameworkWorkflowNotice if n.workflowType == "agent-loop" => n
        }
        val started = agentLoopNotices.exists(_.phase.isInstanceOf[FrameworkWorkflowPhase.Started])
        val failed = agentLoopNotices.exists(_.phase.isInstanceOf[FrameworkWorkflowPhase.Failed])
        started && failed
      }
      def waitForSettle(deadline: Long): Task[Unit] =
        if (settled || System.currentTimeMillis() > deadline) Task.unit
        else Task.sleep(50.millis).flatMap(_ => waitForSettle(deadline))

      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("hi")),
          state = _root_.sigil.signal.EventState.Complete
        ))
        _ <- waitForSettle(System.currentTimeMillis() + 10_000L)
      } yield {
        running.set(false)
        val agentLoopNotices = recorded.iterator().asScala.toList.collect {
          case n: FrameworkWorkflowNotice if n.workflowType == "agent-loop" => n
        }
        val starteds = agentLoopNotices.filter(_.phase.isInstanceOf[FrameworkWorkflowPhase.Started])
        val faileds = agentLoopNotices.filter(_.phase.isInstanceOf[FrameworkWorkflowPhase.Failed])
        starteds should not be empty
        faileds should not be empty
        val failedReason = faileds.head.phase.asInstanceOf[FrameworkWorkflowPhase.Failed].reason
        failedReason should include("synthetic agent-loop crash")
        // The pair shares a workflowId.
        faileds.head.workflowId shouldBe starteds.head.workflowId
        // No `Completed` Notice for the crashed loop — `emitCompleted`
        // is on for the loop's unit, but the body raised, so only
        // `Failed` fires terminally.
        agentLoopNotices.exists(_.phase.isInstanceOf[FrameworkWorkflowPhase.Completed]) shouldBe false
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
