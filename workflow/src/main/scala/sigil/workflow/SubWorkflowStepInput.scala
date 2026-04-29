package sigil.workflow

import fabric.rw.*

/**
 * Step that invokes another workflow inline. The child workflow
 * runs to completion (or fails) before the parent advances.
 *
 * `workflowId` references the persisted target. `variables` is the
 * map of overrides passed to the child's `variableDefs` — values
 * support `{{var}}` substitution from the parent's variable map,
 * so a child workflow can take input from a parent step's output.
 *
 * `output` (optional) names the parent variable that receives the
 * child's final result.
 */
case class SubWorkflowStepInput(id: String,
                                name: String = "",
                                workflowId: String,
                                variables: Map[String, String] = Map.empty,
                                output: String = "") extends WorkflowStepInput derives RW
