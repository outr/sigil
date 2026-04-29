package spec

import fabric.{Json, str}
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.workflow.trigger.{WorkflowEventTrigger, WorkflowEventTriggerImpl}
import strider.Workflow

/**
 * Verifies WorkflowEventTrigger queue lifecycle: refcounted by
 * `register`/`unregister`, drops the queue from the static map when
 * the last listener detaches, and `publishEvent` is a no-op when no
 * listener is registered (so dynamic event names don't accumulate
 * orphan queues).
 */
class WorkflowEventTriggerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def freshName(): String = s"spec-event-${java.util.UUID.randomUUID()}"

  private def workflow: Workflow =
    Workflow(name = "spec", steps = Nil, scheduled = 0L, queue = Nil, sourceId = Id("src"))

  "WorkflowEventTrigger" should {
    "deliver a published payload to a registered listener" in {
      val name = freshName()
      val t = WorkflowEventTriggerImpl(WorkflowEventTrigger(name))
      for {
        _    <- t.register(workflow)
        _    <- WorkflowEventTrigger.publishEvent(name, str("hello"))
        seen <- t.check(workflow)
        _     = seen.map(_.asString) shouldBe Some("hello")
        _    <- t.unregister(workflow)
      } yield succeed
    }

    "drop the queue from the static map when the last listener unregisters" in {
      val name = freshName()
      val t = WorkflowEventTriggerImpl(WorkflowEventTrigger(name))
      for {
        _ <- t.register(workflow)
        _ = WorkflowEventTrigger.isRegistered(name) shouldBe true
        _ <- t.unregister(workflow)
        _ = WorkflowEventTrigger.isRegistered(name) shouldBe false
      } yield succeed
    }

    "keep the queue alive until every listener has unregistered" in {
      val name = freshName()
      val t1 = WorkflowEventTriggerImpl(WorkflowEventTrigger(name))
      val t2 = WorkflowEventTriggerImpl(WorkflowEventTrigger(name))
      for {
        _ <- t1.register(workflow)
        _ <- t2.register(workflow)
        _ <- t1.unregister(workflow)
        _ = WorkflowEventTrigger.isRegistered(name) shouldBe true
        _ <- t2.unregister(workflow)
        _ = WorkflowEventTrigger.isRegistered(name) shouldBe false
      } yield succeed
    }

    "drop publishes for an unknown event name (no orphan queue created)" in {
      val name = freshName()
      WorkflowEventTrigger.publishEvent(name, str("ignored")).map { _ =>
        WorkflowEventTrigger.isRegistered(name) shouldBe false
      }
    }
  }
}
