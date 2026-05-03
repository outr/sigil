package sigil.provider

import lightdb.id.Id
import sigil.db.Model

/**
 * Thrown by the framework's pre-flight gate when a provider request
 * would exceed the model's context window even after the curator's
 * multi-stage shedding (drop unpinned memories, drop unreferenced
 * Information, frame compression) and the provider's own emergency
 * shed (tool-roster trim, last-resort frame drop).
 *
 * The framework's promise: HTTP 400 from the model's "request too
 * long" path never reaches the consumer. If the request fundamentally
 * can't fit (typically: too many pinned memories, or the system
 * prompt itself is larger than the model's window), this exception
 * fires with the diagnostic data for the consumer to act on (e.g.
 * prompt the user to review pinned memories).
 *
 * Pinned memories are NEVER shed — the contract apps rely on when
 * setting [[sigil.conversation.ContextMemory.pinned]] = `true` is
 * "must be in context every turn." If the only way to fit is to drop
 * one, the framework raises this exception instead.
 */
final class RequestOverBudgetException(val estimatedTokens: Int,
                                       val contextLength: Int,
                                       val modelId: Id[Model])
  extends RuntimeException(
    s"Provider request estimated at $estimatedTokens tokens exceeds context window of $contextLength " +
      s"for model ${modelId.value} after all auto-shedding. Critical memories are inviolable; review pinned " +
      s"directives via `list_pinned_memories` and unpin those no longer needed via `unpin_memory(key)`."
  )
