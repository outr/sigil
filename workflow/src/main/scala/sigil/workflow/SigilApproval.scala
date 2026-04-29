package sigil.workflow

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import strider.Workflow
import strider.step.{Approval, Step, TimeoutAction}

/**
 * Strider [[Approval]] subclass that surfaces the approval prompt
 * into the workflow's originating conversation as a Sigil
 * [[sigil.signal.Notice]] and waits for the user's selection.
 *
 * The wait is mediated by `resume_workflow` — the user (or an
 * agent acting as the user's proxy) calls that tool with the
 * chosen option to resolve the pause.
 *
 * `onWaiting` is currently a no-op stub — surfacing the prompt
 * into the conversation as an `Approval` Notice is the next
 * follow-up. The execution path otherwise works: timeout fires
 * the configured `TimeoutAction`.
 */
final case class SigilApproval(input: ApprovalStepInput,
                               id: Id[Step] = Step.id()) extends Approval derives RW {
  override def name: String = if (input.name.nonEmpty) input.name else input.id

  override def prompt: String = input.prompt
  override def options: List[String] = input.options
  override def timeoutMs: Option[Long] = input.timeoutMs
  override def timeoutAction: TimeoutAction = TimeoutAction.Fail

  override def onWaiting(workflow: Workflow): Task[Unit] = Task.unit
}
