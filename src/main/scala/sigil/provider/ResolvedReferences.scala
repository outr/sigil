package sigil.provider

import sigil.conversation.{ContextMemory, ContextSummary}

/**
 * References resolved from the ids carried on a `TurnInput`:
 *   - `criticalMemories` / `memories` → [[ContextMemory]] records
 *   - `summaries` → [[ContextSummary]] records
 *
 * Ids that fail to resolve (deleted, stale) are dropped silently — the
 * curator keeps references consistent.
 *
 * Internal to the framework's translation pass; providers receive a
 * [[ProviderCall]] with rendered `system` text and don't see this type
 * directly.
 */
private[sigil] case class ResolvedReferences(criticalMemories: Vector[ContextMemory],
                                             memories: Vector[ContextMemory],
                                             summaries: Vector[ContextSummary])
