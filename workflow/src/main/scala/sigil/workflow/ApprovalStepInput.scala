package sigil.workflow

import fabric.rw.*
import strider.step.TimeoutAction

/**
 * Step that pauses the workflow and waits for a human decision.
 * The framework surfaces an `Approval` Notice into the originating
 * conversation; a user replies via the `resume_workflow` tool with
 * the chosen option.
 *
 * `prompt` is the question shown to the user. `options` is the list
 * of acceptable responses.
 *
 * `timeoutMs` (optional) bounds how long the workflow waits before
 * the configured `timeoutAction` fires. `None` means wait forever.
 *
 * `timeoutAction` controls what happens when the timeout fires —
 * see [[strider.step.TimeoutAction]]:
 *   - [[TimeoutAction.Fail]] (default) — the run terminates with a
 *     failure event.
 *   - [[TimeoutAction.Proceed]] — the run continues with no recorded
 *     answer; downstream steps see the approval's `output` variable
 *     as empty.
 *   - [[TimeoutAction.Skip]] — the approval step is skipped;
 *     downstream steps run.
 */
case class ApprovalStepInput(id: String,
                             prompt: String,
                             name: Option[String] = None,
                             options: List[String] = List("approve", "reject"),
                             output: Option[String] = None,
                             timeoutMs: Option[Long] = None,
                             timeoutAction: TimeoutAction = TimeoutAction.Fail) extends WorkflowStepInput derives RW
