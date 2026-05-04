package sigil.workflow

import fabric.rw.PolyType

/**
 * Open [[fabric.rw.PolyType]] for the *typed* step shapes agents author.
 * Each subtype maps to one of Strider's primitive `strider.step.Step`
 * variants — `Job`, `Trigger`, `Condition`, `Approval`, `Parallel`,
 * `Loop`, `SubWorkflow` — but with the LLM-facing fields the agent
 * actually fills in (prompts, tool refs, branch IDs, etc.) instead
 * of the implementation-side machinery.
 *
 * The framework's [[WorkflowStepInputCompiler]] folds a
 * `List[WorkflowStepInput]` to the engine's `List[strider.step.Step]`
 * at run time. Agents work in typed land throughout — no raw JSON
 * crosses the tool boundary.
 *
 * Apps that need additional step shapes (custom evaluator hooks,
 * domain-specific compose-time validators) register subtypes via
 * [[sigil.Sigil.workflowStepInputRegistrations]].
 */
trait WorkflowStepInput {

  /** Stable identifier the agent uses to refer to this step from
    * later steps' variable substitutions, condition expressions,
    * `branch` jumps, etc. Required and unique within a workflow. */
  def id: String

  /** Optional human-readable name shown in the workflow's history
    * and surfaced in the conversation as part of step lifecycle
    * Events. Compilers fall back to `id` when None. */
  def name: Option[String]
}

object WorkflowStepInput extends PolyType[WorkflowStepInput]()(using scala.reflect.ClassTag(classOf[WorkflowStepInput]))
