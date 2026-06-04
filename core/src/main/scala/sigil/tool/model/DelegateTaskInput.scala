package sigil.tool.model

import fabric.rw.*
import sigil.provider.Complexity
import sigil.tool.ToolInput

/**
 * Input for `delegate_task` — spawn a worker as a real agent in a
 * sub-conversation linked to the current one (sigil #327, the
 * agent-bridge model). The calling agent stays in that sub-conversation
 * as the worker's supervisor.
 *
 * `role` is the worker's short role name (e.g. `"researcher"`,
 * `"code-reviewer"`) — a flat string, not a nested object, so a small
 * model can fill it reliably (sigil #346 ergonomics). `roleDescription`
 * optionally overrides the worker's identity statement; when omitted the
 * `brief` doubles as it.
 *
 * `brief` is the directive posted to the worker. `goal` is an optional
 * one-sentence statement of the spawning agent's higher-level intent,
 * surfaced separately for forensics.
 *
 * `modelId` is optional. When set, that exact model runs the worker;
 * when unset the framework routes via [[sigil.Sigil.routedModelFor]]
 * (using `complexity` as a filter) and falls back to the spawning
 * agent's model.
 *
 * `complexity` is an optional routing hint applied when the framework
 * resolves the worker's model (only meaningful when `modelId` is unset).
 *
 * `toolNames` is the worker's work roster, on top of the framework
 * essentials it always has (a reply tool + capability discovery). Empty
 * (the default) gives the worker just those essentials — it discovers
 * what it needs via capability search. The worker does NOT inherit the
 * caller's user-facing control surface.
 *
 * `mode` is the worker conversation's operating mode by name. Unset (the
 * default) inherits the spawning conversation's `currentMode`, so a
 * supervisor doing coding work yields a coding worker automatically. Set
 * it to delegate a sub-task that should run in a different mode (e.g. a
 * research supervisor spinning up a `coding` worker); an unknown/blank
 * name falls back to the inherited mode.
 */
case class DelegateTaskInput(role: String,
                             brief: String,
                             goal: Option[String] = None,
                             roleDescription: Option[String] = None,
                             modelId: Option[String] = None,
                             complexity: Option[Complexity] = None,
                             mode: Option[String] = None,
                             toolNames: List[String] = Nil) extends ToolInput derives RW
