package sigil.workflow

import fabric.rw.*

/**
 * Step that pauses the workflow and waits for a human decision.
 * The framework surfaces an `Approval` Notice into the originating
 * conversation; a user replies via the `resume_workflow` tool with
 * the chosen option.
 *
 * `prompt` is the question shown to the user. `options` is the list
 * of acceptable responses — first one is the "default" used by the
 * timeout fallback when `timeoutMs` fires.
 *
 * `timeoutMs` (optional) bounds how long the workflow waits before
 * auto-selecting the first option. None means wait forever.
 */
case class ApprovalStepInput(id: String,
                             name: String = "",
                             prompt: String,
                             options: List[String] = List("approve", "reject"),
                             output: String = "",
                             timeoutMs: Option[Long] = None) extends WorkflowStepInput derives RW
