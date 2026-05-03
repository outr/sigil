package sigil.workflow

import fabric.rw.*

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
 * matches Strider's [[strider.step.TimeoutAction]] cases, parsed
 * case-insensitively:
 *   - `"Fail"` (default) — the run terminates with a failure event.
 *   - `"Proceed"` — the run continues with no recorded answer; downstream
 *     steps see the approval's `output` variable as empty.
 *   - `"Skip"` — the approval step is skipped; downstream steps run.
 *
 * Unknown values fall back to `Fail` (fail-closed — silently proceeding
 * past an approval the user couldn't ack would betray the gate's intent).
 */
case class ApprovalStepInput(id: String,
                             name: String = "",
                             prompt: String,
                             options: List[String] = List("approve", "reject"),
                             output: String = "",
                             timeoutMs: Option[Long] = None,
                             timeoutAction: String = "Fail") extends WorkflowStepInput derives RW
