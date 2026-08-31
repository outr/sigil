package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.conversation.Conversation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-conversation cache of [[MemoryRetrievalResult]] for non-critical
 * memory retrieval. The retrieval is inter-message-stable: the result
 * stays cached across the agent's iteration burst and is invalidated
 * only on (a) a non-agent message landing, (b) a topic-change
 * `Switch` settling, or (c) a memory write that can change what is
 * recallable. The framework's [[sigil.pipeline.MemoryCacheInvalidationEffect]]
 * fires (a) and (b) via [[sigil.Sigil.invalidateMemoryRetrievalCache]];
 * the memory write paths fire (c) via
 * [[sigil.Sigil.invalidateAllMemoryRetrievals]].
 *
 * Goals:
 *   - One Lucene + vector retrieval per user-driven turn (cheap), not
 *     once per agent iteration.
 *   - Predictable membership: once a memory is in the surfaced set, it
 *     stays for the rest of the burst — the agent's reasoning chain
 *     sees a stable context across iterations.
 *   - A revoked memory (rejected, archived, unpinned, re-scoped) stops
 *     surfacing on the next turn rather than riding out the burst.
 *
 * Write invalidation is a global epoch rather than a per-conversation
 * drop: a memory record carries no conversation→space mapping the
 * cache could invert, so a write bumps [[epoch]] and every entry
 * computed at an older epoch recomputes on its next read. The bump is
 * a single atomic increment — memory writes are rare relative to
 * cache reads.
 *
 * The cache is process-local (a [[ConcurrentHashMap]]) and bounded to
 * [[maxEntries]]; the oldest entries are evicted when the bound is
 * exceeded so a long-lived process serving many conversations can't
 * grow it without limit. Apps running many Sigil instances behind a
 * load balancer pin conversations to a single instance per the
 * framework's documented multi-replica routing rule (same as the
 * agent-loop claim cache).
 */
final class MemoryRetrievalCache(val maxEntries: Int = MemoryRetrievalCache.DefaultMaxEntries) {
  private val cache = new ConcurrentHashMap[Id[Conversation], MemoryRetrievalCache.Entry]()
  private val inFlight = new ConcurrentHashMap[Id[Conversation], (Long, Task[MemoryRetrievalResult])]()
  private val epoch = new AtomicLong(0L)
  private val sequence = new AtomicLong(0L)

  /**
   * Return the cached result for `conversationId`, computing and
   * caching it on miss. Concurrent misses for the same conversation
   * share ONE compute — the first caller installs a memoized task
   * every other caller awaits, so a burst of iterations opening
   * together still costs a single Lucene + vector pass (and a single
   * access-record bump). A stale entry (computed before the current
   * write [[epoch]]) counts as a miss.
   */
  def getOrCompute(conversationId: Id[Conversation],
                   compute: => Task[MemoryRetrievalResult]): Task[MemoryRetrievalResult] = Task.defer {
    Option(cache.get(conversationId)) match {
      case Some(entry) if entry.epoch >= epoch.get() => Task.pure(entry.result)
      case _ =>
        // The epoch is captured when the compute is INSTALLED, not per
        // caller: every racer then stores the same value, and a write
        // that lands mid-compute leaves the result stale (recomputed on
        // the next read) rather than passing for fresh.
        val shared = inFlight.computeIfAbsent(conversationId, _ => (epoch.get(), compute.singleton))
        shared._2
          .map { result =>
            cache.put(conversationId, MemoryRetrievalCache.Entry(result, shared._1, sequence.incrementAndGet()))
            evictOverflow()
            result
          }
          .guarantee(Task(inFlight.remove(conversationId, shared)).unit)
    }
  }

  /**
   * Drop the cached entry for `conversationId`. Next `getOrCompute`
   * recomputes. Idempotent — invalidating an empty entry is a no-op.
   */
  def invalidate(conversationId: Id[Conversation]): Unit = {
    cache.remove(conversationId)
    ()
  }

  /**
   * Invalidate every conversation's entry by bumping the write epoch.
   * Entries stay in the map until their next read (which recomputes),
   * so the call is O(1) whatever the cache size. Fired by the memory
   * write paths — a record that stops being recallable must stop
   * rendering on the next turn.
   */
  def invalidateAll(): Unit = {
    epoch.incrementAndGet()
    ()
  }

  /**
   * Drop every entry. Used by [[sigil.Sigil.shutdown]].
   */
  def clear(): Unit = {
    cache.clear()
    inFlight.clear()
  }

  /**
   * Peek at the cache without modifying it. Returns `None` for an
   * entry that a write has since staled. Public test seam — apps
   * shouldn't need this for normal flows.
   */
  def peek(conversationId: Id[Conversation]): Option[MemoryRetrievalResult] =
    Option(cache.get(conversationId)).filter(_.epoch >= epoch.get()).map(_.result)

  /**
   * Live entry count, stale entries included. Test seam for the
   * bound.
   */
  def size: Int = cache.size()

  /**
   * Trim to [[maxEntries]] by dropping the least-recently-computed
   * entries. Cheap in the steady state (one size check); the sort
   * only runs on the rare overflow.
   */
  private def evictOverflow(): Unit =
    if (cache.size() > maxEntries) {
      val excess = cache.size() - maxEntries
      val entries = new java.util.ArrayList(cache.entrySet())
      entries.sort((a, b) => java.lang.Long.compare(a.getValue.sequence, b.getValue.sequence))
      entries.subList(0, math.min(excess, entries.size())).forEach(e => cache.remove(e.getKey, e.getValue))
    }
}

object MemoryRetrievalCache {

  /**
   * Default entry bound. Sized so a busy multi-tenant process keeps
   * every actively-served conversation hot while the map stays a few
   * hundred KB.
   */
  val DefaultMaxEntries: Int = 1000

  /**
   * A cached retrieval plus the write epoch it was computed at and a
   * monotonic sequence for overflow eviction.
   */
  private[compression] case class Entry(result: MemoryRetrievalResult, epoch: Long, sequence: Long)
}
