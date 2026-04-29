package sigil.workflow

import fabric.rw.*

/**
 * Step that branches execution based on a workflow-variable
 * expression. `expression` is a small DSL evaluated against the
 * workflow's current variables — `{{varName}} == "ok"`,
 * `{{count}} > 0`, etc. Truthy → jump to `onTrue`, falsy →
 * `onFalse`. Both target the `id` of another step in the workflow.
 *
 * If a target id doesn't resolve, the workflow fails — same
 * fail-fast contract Strider's primitive `Condition` step has.
 */
case class ConditionStepInput(id: String,
                              name: String = "",
                              expression: String,
                              onTrue: String,
                              onFalse: String) extends WorkflowStepInput derives RW
