package sigil.workflow

import strider.Workflow

/**
 * Sigil-defined keys carried in [[strider.Workflow.variables]].
 *
 * Strider's `variables: Map[String, Json]` is a generic
 * key/value bag. Sigil reserves the `__sigil_*` prefix for
 * framework-level conventions, so app-defined variables can
 * coexist without collision.
 *
 * Bug #65 — `DefaultModelId` lets a workflow author pin a
 * default model at workflow creation; Sigil's job /
 * agent-decision steps fall back to it when their per-step
 * `modelId` is empty. Sourced from `variables` (vs a typed
 * `Workflow.defaultModelId` field) so the framework doesn't
 * require a Strider schema bump for every Sigil-side
 * convention. Apps building workflows by hand set this via:
 *
 * {{{
 *   Workflow(
 *     ...,
 *     variables = Map(
 *       SigilWorkflowVariables.DefaultModelId -> str("openai/gpt-5-haiku")
 *     )
 *   )
 * }}}
 */
object SigilWorkflowVariables {
  /** Reserved variable key for the workflow-level default model
    * id. Steps fall back to the value at this key when their
    * own `modelId` is empty. */
  val DefaultModelId: String = "__sigil_defaultModelId"

  /** Pull the default-model-id off a workflow's variables map,
    * trimming and filtering out empties. Returns `None` when
    * unset. */
  def defaultModelIdOf(workflow: Workflow): Option[String] =
    workflow.variables.get(DefaultModelId)
      .filterNot(_.isNull)
      .map(_.asString.trim)
      .filter(_.nonEmpty)
}
