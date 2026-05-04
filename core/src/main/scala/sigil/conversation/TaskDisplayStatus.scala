package sigil.conversation

import fabric.rw.*

/**
 * Sigil-side display status for [[ConversationTask]] — refines
 * Strider's generic `WorkflowStatus` with worker-specific cases
 * (`WaitingForApproval`, `WaitingForAnswer`) so UI panels can
 * render the right "needs your attention" cue without inspecting
 * the underlying step types.
 *
 * Mapping from `strider.WorkflowStatus` is in
 * [[ConversationTask.fromWorkflow]] — most cases pass through
 * unchanged (`Pending` → `Pending`, `Running` → `Running`, …);
 * `Waiting` splits based on the workflow's current step.
 */
enum TaskDisplayStatus derives RW {
  case Pending             // not yet scheduled
  case Scheduled           // queued, not yet running
  case Running             // a step is actively executing
  case WaitingForAnswer    // suspended on a worker AnswerTrigger
  case WaitingForApproval  // gated on a SigilApproval step
  case Waiting             // generic wait (TimeTrigger, WebhookTrigger, ...)
  case Success
  case Failure
  case Cancelled
  case TimedOut
}
