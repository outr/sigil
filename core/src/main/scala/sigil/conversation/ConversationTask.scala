package sigil.conversation

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import strider.{Workflow, WorkflowStatus}

/**
 * UI-facing summary of an in-flight or recently-settled task in a
 * conversation — workers spawned via `delegate_task`, scheduled
 * workflows tied to the conversation, anything that should appear
 * in a "what's running?" panel. Built on demand by
 * [[sigil.Sigil.activeTasks]] and
 * [[sigil.Sigil.activeTasksFor]] from the underlying workflow run
 * records; not a persisted entity in its own right.
 *
 * The shape is intentionally narrow — the panel needs id, name,
 * status, lifecycle timestamps. Apps wanting deep workflow
 * inspection drill into the underlying [[strider.Workflow]] via
 * the workflow manager directly.
 */
case class ConversationTask(taskId: String,
                            conversationId: Id[Conversation],
                            name: String,
                            status: WorkflowStatus,
                            startedAt: Timestamp,
                            modifiedAt: Timestamp,
                            workflowSourceId: String) derives RW

object ConversationTask {

  /** Project a Strider [[Workflow]] run into the panel-display
    * [[ConversationTask]]. Workflows without a `conversationId`
    * (autonomous cron / background) return None — those don't
    * belong to any conversation's panel. */
  def fromWorkflow(wf: Workflow): Option[ConversationTask] =
    wf.conversationId.map { convIdStr =>
      ConversationTask(
        taskId           = wf._id.value,
        conversationId   = Id[Conversation](convIdStr),
        name             = wf.name,
        status           = wf.status,
        startedAt        = wf.created,
        modifiedAt       = wf.modified,
        workflowSourceId = wf.sourceId.value
      )
    }
}
