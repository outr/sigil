package sigil.workflow

import fabric.rw.*

/**
 * Step that iterates `body` over a workflow-variable list.
 *
 * `over` is the variable name holding the source list (must be a
 * JSON array; non-array values fail the step). `itemVariable` is
 * the variable name each iteration's element is bound to before
 * `body` runs — defaults to `"item"`.
 *
 * `output` (optional) collects each iteration's last-step output
 * into a list under that variable name. Empty means "discard
 * iteration outputs."
 */
case class LoopStepInput(id: String,
                         name: String = "",
                         over: String,
                         itemVariable: String = "item",
                         body: List[WorkflowStepInput],
                         output: String = "") extends WorkflowStepInput derives RW
