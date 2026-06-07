package sigil.workflow

import fabric.rw.*

/**
 * Step that iterates `body` over a workflow-variable list.
 *
 * `over` is the variable name holding the source. It's coerced into
 * iteration items: a JSON array iterates as-is; a newline string splits
 * into trimmed, non-empty lines; an object exposes a text blob via its
 * `text` field or a list via its first array field. So a discovery step's
 * result (e.g. `grep`'s `{"text":"A\nB"}`) can drive the loop directly via
 * that step's `output` variable — no hand-built array. `itemVariable` is
 * the variable name each iteration's element is bound to before `body`
 * runs — defaults to `"item"`.
 *
 * `output` (optional) collects each iteration's last-step output
 * into a list under that variable name. Empty means "discard
 * iteration outputs."
 */
case class LoopStepInput(id: String,
                         over: String,
                         body: List[WorkflowStepInput],
                         name: Option[String] = None,
                         itemVariable: String = "item",
                         output: Option[String] = None) extends WorkflowStepInput derives RW
