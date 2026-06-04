package sigil.conversation

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.workflow.SigilApproval
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
   * Project a worker sub-conversation (sigil #327 agent-bridge
   * delegation) into a panel task. `active` reflects the worker
   * agent's current [[sigil.event.AgentState]] — `Running` while it
   * holds an Active claim (mid-turn), `Waiting` once it has yielded
   * (responded; awaiting its supervisor). The task is keyed by the
   * worker conversation id and attributed to the parent conversation
   * that spawned it.
   */
  def fromWorkerConversation(conv: Conversation, active: Boolean): ConversationTask =
    ConversationTask(
      taskId = conv._id.value,
      conversationId = conv.parentConversationId.getOrElse(conv._id),
      name = conv.currentTopic.label,
      status = if (active) WorkflowStatus.Running else WorkflowStatus.Waiting,
      displayStatus = if (active) TaskDisplayStatus.Running else TaskDisplayStatus.Waiting,
      startedAt = conv.created,
      modifiedAt = conv.modified,
      workflowSourceId = "worker"
    )

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
        case _ => TaskDisplayStatus.Waiting
      }
    case WorkflowStatus.Paused => TaskDisplayStatus.Paused
  }
}
