package sigil.tool.model

import fabric.rw.*
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
 * `modelId` is required for v1 — the spawning agent picks it from
 * the role's WorkType via `ProviderStrategy.routed` before calling
 * this tool. Future versions may take it implicitly via the
 * strategy machinery.
 */
case class DelegateTaskInput(role: Role,
                             brief: String,
                             modelId: String) extends ToolInput derives RW
