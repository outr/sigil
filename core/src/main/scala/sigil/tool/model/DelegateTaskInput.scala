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
 *
 * `toolNames` is the worker's tool roster — names looked up against
 * the host's `findTools` registry. The framework always appends
 * `complete_task` so workers can terminate via typed tool call.
 * Empty list means a "pure reasoning" worker (text-only ReAct via
 * markers; no tool dispatch).
 */
case class DelegateTaskInput(role: Role,
                             brief: String,
                             modelId: String,
                             toolNames: List[String] = Nil)
  extends ToolInput derives RW
