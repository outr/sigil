package sigil.conversation

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.workflow.SigilApproval
import sigil.workflow.trigger.AnswerTriggerImpl
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
 *
 * `status` is Strider's raw lifecycle status. `displayStatus` is
 * Sigil's UI-friendly refinement that splits `Waiting` into
 * `WaitingForApproval` / `WaitingForAnswer` / generic `Waiting`
 * based on what step the workflow is currently parked on, so the
 * panel can show "needs YOUR approval" vs "waiting on a timer."
 */
case class ConversationTask(taskId: String,
                            conversationId: Id[Conversation],
                            name: String,
                            status: WorkflowStatus,
                            displayStatus: TaskDisplayStatus,
                            startedAt: Timestamp,
                            modifiedAt: Timestamp,
                            workflowSourceId: String)
  derives RW

object ConversationTask {

  /**
   * Project a Strider [[Workflow]] run into the panel-display
   * [[ConversationTask]]. Workflows without a `conversationId`
   * (autonomous cron / background) return None — those don't
   * belong to any conversation's panel.
   */
  def fromWorkflow(wf: Workflow): Option[ConversationTask] =
    wf.conversationId.map { convIdStr =>
      ConversationTask(
        taskId = wf._id.value,
        conversationId = Id[Conversation](convIdStr),
        name = wf.name,
        status = wf.status,
        displayStatus = computeDisplayStatus(wf),
        startedAt = wf.created,
        modifiedAt = wf.modified,
        workflowSourceId = wf.sourceId.value
      )
    }

  /**
   * Refine Strider's lifecycle status with Sigil-specific
   * waiting flavors based on which step the run is parked on.
   */
  private def computeDisplayStatus(wf: Workflow): TaskDisplayStatus = wf.status match {
    case WorkflowStatus.Pending => TaskDisplayStatus.Pending
    case WorkflowStatus.Scheduled => TaskDisplayStatus.Scheduled
    case WorkflowStatus.Running => TaskDisplayStatus.Running
    case WorkflowStatus.Success => TaskDisplayStatus.Success
    case WorkflowStatus.Failure => TaskDisplayStatus.Failure
    case WorkflowStatus.Cancelled => TaskDisplayStatus.Cancelled
    case WorkflowStatus.TimedOut => TaskDisplayStatus.TimedOut
    case WorkflowStatus.Waiting =>
      val waitingStep = wf.waitingStepId.flatMap(id => wf.byStepId(id))
      waitingStep match {
        case Some(_: SigilApproval) => TaskDisplayStatus.WaitingForApproval
        case Some(_: AnswerTriggerImpl) => TaskDisplayStatus.WaitingForAnswer
        case _ => TaskDisplayStatus.Waiting
      }
  }
}
