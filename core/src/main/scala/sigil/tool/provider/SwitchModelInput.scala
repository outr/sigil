package sigil.tool.provider

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Args for [[SwitchModelTool]]. The `query` accepts:
 *
 *   - **A model id** (e.g. `"anthropic/claude-sonnet-4-6"`) — the
 *     tool creates an ad-hoc single-model
 *     [[sigil.provider.ProviderStrategyRecord]] under the
 *     conversation's space and assigns it. Subsequent turns use that
 *     model exclusively.
 *   - **A strategy label or id** (e.g. `"Balanced"`,
 *     `"Premium Quality"`, or a record id) — the tool finds the
 *     matching saved strategy and assigns it.
 *   - **`"auto"` / `"default"`** — un-assigns the current strategy
 *     so dispatch falls back to the agent's pinned model.
 *
 * Ambiguous queries return a disambiguation list rather than picking
 * the first match.
 */
case class SwitchModelInput(query: String) extends ToolInput derives RW
