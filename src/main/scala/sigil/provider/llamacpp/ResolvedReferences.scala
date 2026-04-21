package sigil.provider.llamacpp

import sigil.conversation.{ContextMemory, ContextSummary}

/**
 * References resolved from the ids carried on a `TurnInput`:
 *   - `criticalMemories` / `memories` → [[ContextMemory]] records
 *   - `summaries` → [[ContextSummary]] records
 *
 * Ids that fail to resolve (deleted, stale) are dropped silently — the
 * curator keeps references consistent.
 */
private[llamacpp] case class ResolvedReferences(criticalMemories: Vector[ContextMemory],
                                                memories: Vector[ContextMemory],
                                                summaries: Vector[ContextSummary])
