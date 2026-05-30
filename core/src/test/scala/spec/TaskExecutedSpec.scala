package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, ContextFrame, Topic, FrameBuilder}
import sigil.dispatcher.TriggerFilter
import sigil.event.Event
import sigil.participant.DefaultAgentParticipant
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
        participantId        = TestUser,
        conversationId       = Conversation.id("user-facing"),
        topicId              = TestTopicId,
        taskId               = "wf-abc-123",
        roleName             = "researcher",
        summary              = "Found 3 papers; cited the strongest 2 in the brief.",
        iterations           = 4,
        exhausted            = false,
        workerConversationId = Some(Conversation.id("worker-scratchpad"))
      )
      val rw = summon[RW[TaskExecuted]]
      rw.write(rw.read(ev)) shouldBe ev
      rapid.Task.pure(succeed)
    }

    "carry the exhausted flag when the worker hit maxIterations" in {
      val ev = TaskExecuted(
        participantId  = TestUser,
        conversationId = Conversation.id("uf"),
        topicId        = TestTopicId,
        taskId         = "wf-1",
        roleName       = "researcher",
        summary        = "Best-effort response after iteration cap.",
        iterations     = 50,
        exhausted      = true
      )
      ev.exhausted shouldBe true
      ev.workerConversationId shouldBe None
      rapid.Task.pure(succeed)
    }

    // #323 — a worker completion is a ControlPlaneEvent, not a Message,
    // so the default TriggerFilter rules missed it and it never woke the
    // parent agent; FrameBuilder excluded it from the prompt by the
    // catch-all ControlPlaneEvent => None. Both paths must now carry it.
    "wake the parent agent (TriggerFilter) when it settles into the parent conversation" in {
      val parent = DefaultAgentParticipant(
        id      = TestAgent,
        modelId = sigil.db.Model.id("test", "model")
      )
      val ev = TaskExecuted(
        participantId  = TestUser,
        conversationId = Conversation.id("uf"),
        topicId        = TestTopicId,
        taskId         = "wf-7",
        roleName       = "researcher",
        summary        = "Indexed all bug docs.",
        iterations     = 6
      )
      TriggerFilter.isTriggerFor(parent, ev) shouldBe true
      rapid.Task.pure(succeed)
    }

    "render as a non-empty System frame carrying the worker summary (FrameBuilder)" in {
      val ev = TaskExecuted(
        participantId  = TestUser,
        conversationId = Conversation.id("uf"),
        topicId        = TestTopicId,
        taskId         = "wf-8",
        roleName       = "researcher",
        summary        = "Found 3 references to bug #325.",
        iterations     = 4
      )
      val frame = FrameBuilder.computeFrame(ev)
      frame match {
        case Some(s: ContextFrame.System) =>
          s.content should include ("Found 3 references to bug #325.")
          s.content should include ("researcher")
        case other => fail(s"expected a non-empty System frame, got $other")
      }
      rapid.Task.pure(succeed)
    }

    "flag an exhausted worker in the rendered frame" in {
      val ev = TaskExecuted(
        participantId  = TestUser,
        conversationId = Conversation.id("uf"),
        topicId        = TestTopicId,
        taskId         = "wf-9",
        roleName       = "researcher",
        summary        = "Best-effort after cap.",
        iterations     = 50,
        exhausted      = true
      )
      FrameBuilder.computeFrame(ev) match {
        case Some(s: ContextFrame.System) => s.content should include ("iteration cap")
        case other                        => fail(s"expected a System frame, got $other")
      }
      rapid.Task.pure(succeed)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
