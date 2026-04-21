package sigil.provider.llamacpp

import sigil.conversation.ContextMemory

/**
 * Memory records resolved from the ids in `ConversationContext.criticalMemories`
 * and `ConversationContext.memories`. Passed internally in
 * [[LlamaCppProvider]] from `requestConverter` (which does the DB lookups)
 * into `buildBody` / `buildSystemContent` (which render the records).
 *
 * Ids that didn't resolve — deleted memories, stale references — are
 * silently dropped by the resolver; by the time `ResolvedMemories` is
 * constructed, only extant records survive.
 */
private[llamacpp] case class ResolvedMemories(critical: Vector[ContextMemory],
                                              regular: Vector[ContextMemory])
