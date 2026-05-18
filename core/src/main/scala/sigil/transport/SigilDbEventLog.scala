package sigil.transport

import lightdb.id.Id as LId
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.signal.Signal
import spice.http.durable.EventLog

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-through adapter that surfaces [[sigil.db.SigilDB.events]] as a
 * spice [[EventLog]]. The wire channel carries the full
 * [[sigil.signal.Signal]] sum (Events, Deltas, Notices), so this log
 * accepts every Signal subtype on `append` even though only Events
 * are durably persisted (in `SigilDB.events`, by `Sigil.publish`).
 *
 *   - **`append`** is a no-op on the persistence side; it only
 *     returns a strictly-monotonic seq per channel that spice's
 *     `SequenceTracker` accepts. Events bias the seq toward
 *     `event.timestamp.value` so resume-cursor math against
 *     `SigilDB.events` aligns; Deltas and Notices fall back to
 *     `System.currentTimeMillis()`. The per-channel
 *     [[AtomicLong]] guarantees `seq > previousSeq` even when the
 *     hint stalls or goes backwards.
 *   - **`replay`** queries `SigilDB.events` filtered by channel
 *     (conversationId) and `timestamp > afterSeq`. Returns Events
 *     only — Deltas and Notices that fired during a disconnect
 *     window are not durably replayed (the settled Event captures
 *     their outcome; Notices regenerate next turn if still
 *     applicable). Within-session reconnect still gets full Signal
 *     replay through spice's in-memory ring; only across-session /
 *     post-restart reconnect bottoms out at this Event-subset.
 *
 * Apps that key channels differently (per-viewer, composite) supply
 * their own EventLog implementation — `SignalTransport.attach`
 * doesn't need this adapter at all (it queries `SigilDB.events`
 * directly).
 */
final class SigilDbEventLog(sigil: Sigil) extends EventLog[LId[Conversation], Signal] {

  private val seqCounters: ConcurrentHashMap[LId[Conversation], AtomicLong] =
    new ConcurrentHashMap[LId[Conversation], AtomicLong]()

  /**
   * Per-channel monotonic seq. `hint` biases the value (Event
   * timestamp, or now) but the result is always > the previous
   * seq returned for the channel.
   */
  private def nextSeq(channelId: LId[Conversation], hint: Long): Long = {
    val counter = seqCounters.computeIfAbsent(channelId, _ => new AtomicLong(0L))
    counter.updateAndGet(prev => math.max(prev + 1, hint))
  }

  override def append(channelId: LId[Conversation], signal: Signal): Task[Long] = signal match {
    case e: Event => Task.pure(nextSeq(channelId, e.timestamp.value))
    case _ => Task.pure(nextSeq(channelId, System.currentTimeMillis()))
  }

  override def replay(channelId: LId[Conversation], afterSeq: Long): Task[List[(Long, Signal)]] =
    sigil.withDB(_.events.transaction(_.list)).map { all =>
      all.iterator
        .filter(e => e.conversationId == channelId && e.timestamp.value > afterSeq)
        .toList
        .sortBy(_.timestamp.value)
        .map(e => (e.timestamp.value, e: Signal))
    }
}
