package sigil.db

import lightdb.id.Id
import sigil.event.Event

import scala.collection.concurrent.TrieMap

/**
 * Per-iteration batched-write scope for the `events` store.
 *
 * [[sigil.db.SigilDB.withBatchedEvents]] registers one of these per
 * open batched transaction, keyed by `conversationId`. It carries:
 *
 *   - `transaction` — the single `events` transaction held open for
 *     the whole agent-loop iteration. Every batched read / write for
 *     the conversation routes onto it so the iteration costs one
 *     `IndexWriter.commit` fsync.
 *   - `inFlight` — a concurrency-safe accumulator of the events
 *     written into `transaction` so far this iteration. Keyed by
 *     [[Event]] id with last-write-wins semantics: a Delta that
 *     mutates an already-accumulated event upserts the post-delta
 *     version so the accumulator always holds the latest state of
 *     each in-flight event, never one entry per Delta.
 *
 * The accumulator is the in-flight-merge source for
 * [[sigil.Sigil.eventsFor]] page 0. An event published mid-iteration
 * is hub-broadcast immediately but not committed to the DB until the
 * scope exits; a session joining the conversation after that
 * broadcast would otherwise see the event from neither the live
 * stream nor a committed-only read. `eventsFor` page 0 snapshots
 * `inFlight` and merges it with the committed page so the joining
 * session's first read is complete.
 *
 * Concurrency: the accumulator is written by the agent-loop fiber
 * and read (snapshotted) by joining-session transport fibers.
 * [[TrieMap]] plus reading via `.values` (a consistent point-in-time
 * snapshot) is the lock-free pattern — no lock is held across the
 * read.
 *
 * The `TX` type parameter is the `events` store's path-dependent
 * transaction type; [[sigil.db.SigilDB]] supplies it as `events.TX`.
 */
final class BatchedEventScope[TX](val transaction: TX) {

  /**
   * Events written into [[transaction]] during this scope, keyed by
   * id. Last-write-wins — Delta upserts replace the prior version.
   */
  val inFlight: TrieMap[Id[Event], Event] = TrieMap.empty

  /**
   * Record an event written into the batched transaction. Called by
   * [[sigil.db.SigilDB.apply]] for every Event insert and for the
   * post-delta-applied Event of every Delta upsert. Idempotent on
   * id — re-recording the same id overwrites with the newer value.
   */
  def record(event: Event): Unit = {
    inFlight.put(event._id, event)
    ()
  }

  /**
   * Point-in-time snapshot of every in-flight event. Safe to call
   * from a fiber other than the writer; the returned list does not
   * alias the live map.
   */
  def snapshot: List[Event] = inFlight.values.toList
}
