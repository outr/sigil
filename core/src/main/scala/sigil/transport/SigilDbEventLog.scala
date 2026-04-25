package sigil.transport

import lightdb.id.Id as LId
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Event
import spice.http.durable.EventLog

/**
 * Read-through adapter that surfaces [[sigil.db.SigilDB.events]] as a
 * spice [[EventLog]]. Use it when wiring a `DurableSocketServer`:
 * the server calls `eventLog.append(channelId, event)` on broadcast
 * and `eventLog.replay(channelId, afterSeq)` on resume.
 *
 *   - **`append`** is a no-op on the persistence side. Events already
 *     reach `SigilDB.events` via [[sigil.Sigil.publish]] before they
 *     hit the wire. We simply return `event.timestamp.value` as the
 *     seq, so the wire-side seq matches the resume cursor used
 *     elsewhere in the transport stack.
 *   - **`replay`** queries `SigilDB.events`, filters by channel
 *     (conversationId) and `timestamp > afterSeq`, and returns
 *     `(timestamp, event)` pairs in chronological order.
 *
 * The "channel id" is a [[lightdb.id.Id]]`[Conversation]`. Apps that
 * key channels differently (per-viewer, composite) supply their own
 * EventLog implementation — `SignalTransport.attach` doesn't need
 * this adapter at all (it queries `SigilDB.events` directly).
 */
final class SigilDbEventLog(sigil: Sigil) extends EventLog[LId[Conversation], Event] {

  override def append(channelId: LId[Conversation], event: Event): Task[Long] =
    Task.pure(event.timestamp.value)

  override def replay(channelId: LId[Conversation], afterSeq: Long): Task[List[(Long, Event)]] =
    sigil.withDB(_.events.transaction(_.list)).map { all =>
      all.iterator
        .filter(e => e.conversationId == channelId && e.timestamp.value > afterSeq)
        .toList
        .sortBy(_.timestamp.value)
        .map(e => (e.timestamp.value, e))
    }
}
