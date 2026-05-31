package sigil.tool.model

import fabric.rw.*
import sigil.provider.Complexity
import sigil.role.Role
import sigil.tool.ToolInput

/**
 * Input for `delegate_task` — spawn a worker as a real agent in a
 * sub-conversation linked to the current one (sigil #327, the
 * agent-bridge model). The calling agent stays in that sub-conversation
 * as the worker's supervisor.
 *
 * `role` carries the worker's identity (description, optional skill,
 * [[sigil.provider.WorkType]]) and drives its system prompt; `brief` is
 * the directive posted to the worker.
 *
 * `goal` — optional one-sentence statement of the spawning agent's
 * higher-level intent, surfaced separately from `brief` for forensics.
 *
 * `modelId` is optional. When set, that exact model runs the worker;
 * when unset the framework routes via [[sigil.Sigil.routedModelFor]]
 * using `role.workType` (and `complexity` as a filter), falling back to
 * the spawning agent's model.
 *
 * `complexity` is an optional routing hint applied when the framework
 * resolves the worker's model (only meaningful when `modelId` is unset)
 * — filters the strategy's candidate chain by `supportedComplexity`.
 *
 * `toolNames` is the worker's work roster, on top of the framework
 * essentials it always has (a reply tool + capability discovery). Empty
 * (the default) gives the worker just those essentials — it discovers
 * what it needs via capability search. The worker does NOT inherit the
 * caller's user-facing control surface.
 */
case class DelegateTaskInput(role: Role,
                             brief: String,
                             goal: Option[String] = None,
                             modelId: Option[String] = None,
                             complexity: Option[Complexity] = None,
                             toolNames: List[String] = Nil) extends ToolInput derives RW
