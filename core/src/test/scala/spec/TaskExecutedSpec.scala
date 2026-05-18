package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, Topic}
import sigil.event.Event
import sigil.workflow.event.TaskExecuted

/**
 * Round-trip coverage for the worker-shaped settle Event.
 * `TaskExecuted` fires into the parent (user-facing) conversation
 * when a worker run settles, carrying the summary, role name, and
 * iteration count without consumers having to walk step results.
 */
class TaskExecutedSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "TaskExecuted" should {
    "round-trip through fabric RW with all worker-summary fields preserved" in {
      val ev = TaskExecuted(
        participantId = TestUser,
        conversationId = Conversation.id("user-facing"),
        topicId = TestTopicId,
        taskId = "wf-abc-123",
        roleName = "researcher",
        summary = "Found 3 papers; cited the strongest 2 in the brief.",
        iterations = 4,
        exhausted = false,
        workerConversationId = Some(Conversation.id("worker-scratchpad"))
      )
      val rw = summon[RW[TaskExecuted]]
      rw.write(rw.read(ev)) shouldBe ev
      rapid.Task.pure(succeed)
    }

    "carry the exhausted flag when the worker hit maxIterations" in {
      val ev = TaskExecuted(
        participantId = TestUser,
        conversationId = Conversation.id("uf"),
        topicId = TestTopicId,
        taskId = "wf-1",
        roleName = "researcher",
        summary = "Best-effort response after iteration cap.",
        iterations = 50,
        exhausted = true
      )
      ev.exhausted shouldBe true
      ev.workerConversationId shouldBe None
      rapid.Task.pure(succeed)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
