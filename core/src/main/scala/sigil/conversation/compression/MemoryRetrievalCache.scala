package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.conversation.Conversation

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-conversation cache of [[MemoryRetrievalResult]] for non-critical
 * memory retrieval. The retrieval is inter-message-stable: the result
 * stays cached across the agent's iteration burst and is invalidated
 * only on (a) a non-agent message landing or (b) a topic-change
 * `Switch` settling. The framework's [[sigil.pipeline.MemoryCacheInvalidationEffect]]
 * fires invalidation via [[sigil.Sigil.invalidateMemoryRetrievalCache]].
 *
 * Goals:
 *   - One Lucene + vector retrieval per user-driven turn (cheap), not
 *     once per agent iteration.
 *   - Predictable membership: once a memory is in the surfaced set, it
 *     stays for the rest of the burst — the agent's reasoning chain
 *     sees a stable context across iterations.
 *   - No tunable knobs (no decay, no jitter): the boundary is the next
 *     non-agent message OR a real topic shift.
 *
 * The cache is process-local (a [[ConcurrentHashMap]]). Apps running
 * many Sigil instances behind a load balancer pin conversations to a
 * single instance per the framework's documented multi-replica
 * routing rule (same as the agent-loop claim cache).
 */
final class MemoryRetrievalCache {
  private val cache = new ConcurrentHashMap[Id[Conversation], MemoryRetrievalResult]()

  /** Return the cached result for `conversationId`, computing and
    * caching it on miss. The compute thunk runs at most once per
    * (conversationId, cache-lifetime). */
  def getOrCompute(conversationId: Id[Conversation],
                   compute: => Task[MemoryRetrievalResult]): Task[MemoryRetrievalResult] =
    Option(cache.get(conversationId)) match {
      case Some(hit) => Task.pure(hit)
      case None      => compute.flatMap { result =>
        Task { cache.put(conversationId, result); result }
      }
    }

  /** Drop the cached entry for `conversationId`. Next `getOrCompute`
    * recomputes. Idempotent — invalidating an empty entry is a no-op. */
  def invalidate(conversationId: Id[Conversation]): Unit = {
    cache.remove(conversationId)
    ()
  }

  /** Drop every entry. Used by [[sigil.Sigil.shutdown]]. */
  def clear(): Unit = cache.clear()

  /** Peek at the cache without modifying it. Public test seam — apps
    * shouldn't need this for normal flows. */
  def peek(conversationId: Id[Conversation]): Option[MemoryRetrievalResult] =
    Option(cache.get(conversationId))
}
