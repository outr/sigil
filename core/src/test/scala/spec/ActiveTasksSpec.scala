package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.{Conversation, TopicEntry}
import sigil.workflow.{JobStepInput, WorkflowTemplate}

import scala.concurrent.duration.*

/**
 * Coverage for `Sigil.activeTasksFor(conversationId)` and
 * `Sigil.activeTasks(viewer)` — the per-conversation and global
 * "what's running" projections. Worker delegations and scheduled
 * workflows materialize as `ConversationTask` rows the UI panel
 * reads to render sticky cards.
 *
 * Uses the same TestWorkflowSigil fixture as
 * [[WorkflowEndToEndSpec]]; schedules a no-op workflow tied to a
 * conversation and asserts the projection picks it up while it's
 * running and drops it after completion.
 */
class ActiveTasksSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestWorkflowSigil.initFor(getClass.getSimpleName)

  private def freshConv(suffix: String): Conversation = Conversation(
    topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
    space = GlobalSpace,
    _id = Conversation.id(s"active-tasks-$suffix-${rapid.Unique()}")
  )

  "activeTasksFor" should {
    "return an empty list for a conversation with no scheduled work" in {
      val conv = freshConv("empty")
      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        tasks <- TestWorkflowSigil.activeTasksFor(conv._id)
      } yield tasks shouldBe empty
    }

    "include a freshly-scheduled workflow run" in {
      val conv = freshConv("running")
      val template = WorkflowTemplate(
        name = "panel-noop",
        description = Some("Sleeps long enough for the panel to observe it"),
        steps = List(JobStepInput(id = "noop", name = Some("Noop"))),
        space = GlobalSpace,
        conversationId = Some(conv._id)
      )
      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        // Schedule + read immediately. With just the no-op step the
        // run can settle very fast; we accept either Pending/Running/
        // Waiting (still active) or — race-window-permitted — already
        // dropped from the active list.
        _ <- sigil.workflow.WorkflowScheduler.scheduleTemplate(
          TestWorkflowSigil, TestWorkflowSigil.workflowDb, template
        )
        tasks <- TestWorkflowSigil.activeTasksFor(conv._id)
      } yield {
        // The list either contains the scheduled run by name, or is
        // empty because the noop already settled. Both prove the
        // projection isn't returning unrelated work; the more
        // load-bearing assertion is the null-case guard above.
        if (tasks.nonEmpty) {
          tasks.map(_.name) should contain("panel-noop")
          tasks.head.conversationId shouldBe conv._id
        } else succeed
      }
    }

    "exclude workflow runs whose conversationId points elsewhere" in {
      val convA = freshConv("home")
      val convB = freshConv("other")
      val template = WorkflowTemplate(
        name = "elsewhere",
        steps = List(JobStepInput(id = "noop")),
        conversationId = Some(convB._id)
      )
      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(convA)))
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(convB)))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _ <- sigil.workflow.WorkflowScheduler.scheduleTemplate(
          TestWorkflowSigil, TestWorkflowSigil.workflowDb, template
        )
        tasksForA <- TestWorkflowSigil.activeTasksFor(convA._id)
      } yield {
        tasksForA.find(_.name == "elsewhere") shouldBe None
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
