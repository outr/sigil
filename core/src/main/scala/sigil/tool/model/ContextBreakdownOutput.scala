package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.context.ContextBreakdownTool]] — a
 * section-by-section view of where the current turn's context window
 * is being spent.
 *
 *   - `totalTokens` — the sum of every section's token contribution.
 *   - `currentMode` — the conversation's active mode name.
 *   - `sections` — per-region token breakdown.
 *   - `note` — a caveat that token counts are heuristic estimates;
 *     production budget enforcement uses the provider's tokenizer.
 */
case class ContextBreakdownOutput(totalTokens: Int,
                                  currentMode: String,
                                  sections: List[ContextSectionBreakdown],
                                  note: String) derives RW
