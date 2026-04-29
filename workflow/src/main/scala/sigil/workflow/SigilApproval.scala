package sigil.workflow

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.conversation.Conversation
import sigil.workflow.signal.WorkflowApprovalRequested
import strider.Workflow
import strider.step.{Approval, Step, TimeoutAction}

/**
 * Strider [[Approval]] subclass that surfaces the approval prompt
 * into the workflow's originating conversation as a Sigil
 * [[WorkflowApprovalRequested]] Notice and waits for the user's
 * selection.
 *
 * The wait is mediated by `resume_workflow` — the user (or an
 * agent acting as the user's proxy) calls that tool with the
 * chosen option to resolve the pause. Cron-fired runs without a
 * conversation context skip the Notice (no audience to surface
 * to); the timeout still fires the configured `TimeoutAction`.
 */
final case class SigilApproval(input: ApprovalStepInput,
                               id: Id[Step] = Step.id()) extends Approval derives RW {
  override def name: String = if (input.name.nonEmpty) input.name else input.id

  override def prompt: String = input.prompt
  override def options: List[String] = input.options
  override def timeoutMs: Option[Long] = input.timeoutMs
  override def timeoutAction: TimeoutAction = TimeoutAction.Fail

  override def onWaiting(workflow: Workflow): Task[Unit] =
    workflow.conversationId match {
      case None => Task.unit
      case Some(convIdStr) =>
        val notice = WorkflowApprovalRequested(
          conversationId = Id[Conversation](convIdStr),
          runId          = workflow._id.value,
          stepId         = id.value,
          stepName       = name,
          prompt         = prompt,
          options        = options,
          timeoutMs      = timeoutMs
        )
        WorkflowHost.get.publish(notice)
    }
}
