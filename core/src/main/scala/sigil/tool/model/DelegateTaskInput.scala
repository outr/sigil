package sigil.tool.model

import fabric.rw.*
import sigil.provider.Complexity
import sigil.role.Role
import sigil.tool.ToolInput

/**
 * Input for `delegate_task` — spawn a worker that runs an
 * [[sigil.workflow.AgentDecisionStepInput]] under a fresh
 * scratchpad conversation linked to the current conversation as
 * its parent.
 *
 * `role` carries the worker's identity (description, optional
 * skill, [[sigil.provider.WorkType]]). The worker's first iteration
 * runs an LLM round-trip with `role.description` as system prompt
 * and `brief` as the user directive.
 *
 * `goal` is a one-sentence statement of what the spawning agent
 * is trying to learn / accomplish — surfaced separately from
 * `brief` (the worker's full instructions) so the parent's
 * forensics and the worker's setup prompt can reference the
 * higher-level intent independently of the detailed directive.
 * Optional; defaults to None.
 *
 * `modelId` is optional. When set, that exact model runs the
 * worker. When unset, the framework resolves a model via
 * [[sigil.Sigil.routedModelFor]] using `role.workType` (with
 * `complexity` as an optional filter) and the spawning agent's
 * `modelId` as the fallback if no strategy candidate fits.
 *
 * `complexity` is an optional routing hint applied when the
 * framework resolves the worker's model (only meaningful when
 * `modelId` is unset). Sigil bug #289 — filters the strategy's
 * candidate chain by `supportedComplexity` so a spawning agent
 * can say "give this worker a High-tier model" without enumerating
 * a specific id. Unset (the default) means the strategy's normal
 * candidate ordering applies.
 *
 * `toolNames` is the worker's tool roster — names looked up against
 * the host's `findTools` registry. The framework always appends
 * `complete_task` so workers can terminate via typed tool call.
 * Empty list (the default) means **inherit the spawning agent's
 * effective roster** — the worker gets the same tools the parent
 * has access to. The agent can pass an explicit subset (or
 * superset, when paired with `find_capability`) to restrict or
 * expand.
 *
 * `maxIterations` caps the worker's agent-loop iterations. When
 * unset, the framework default (50) applies.
 */
case class DelegateTaskInput(role: Role,
                             brief: String,
                             goal: Option[String] = None,
                             modelId: Option[String] = None,
                             complexity: Option[Complexity] = None,
                             toolNames: List[String] = Nil,
                             maxIterations: Option[Int] = None) extends ToolInput derives RW
