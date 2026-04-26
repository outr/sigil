package sigil.transport

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.participant.ParticipantId
import sigil.signal.{Delta, Signal}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/**
 * Bridges a [[Sigil]] to a [[SignalSink]]: replays history from
 * [[sigil.db.SigilDB.events]] (per `resume`), then forwards
 * `signalsFor(viewer)` live until detach.
 *
 * No in-memory replay buffer — durable history lives in the events
 * store; resume queries it directly. The only buffering is the
 * standard per-subscriber queue inside [[sigil.pipeline.SignalHub]].
 *
 * Race-safety: subscribes to live first (events accumulate in the
 * hub's queue), runs the DB replay, and then forwards live signals
 * filtered to skip Events already covered by replay. Deltas pass
 * through unconditionally — they describe in-flight state that's not
 * separately persisted.
 */
final class SignalTransport(sigil: Sigil) {

  /**
   * Restrict replay (and live forwarding) to events from a specific
   * set of conversations. `None` (the default) replays everything the
   * viewer's transforms don't drop. Apps that scope wire delivery to
   * a per-conversation channel pass the set explicitly.
   */
  type ConversationFilter = Option[Set[Id[Conversation]]]

  /**
   * Subscribe `sink` to this `viewer`'s signals.
   *
   *   1. Subscribe to `signalsFor(viewer)` — events buffer in the
   *      hub's per-subscriber queue while replay runs.
   *   2. Resolve replay events from `SigilDB.events` per `resume`.
   *   3. Push replayed events to the sink in chronological order,
   *      applying `viewerTransforms` per event. Track the latest
   *      `timestamp` seen (the replay boundary).
   *   4. Forward live signals — Events with `timestamp` ≤ boundary are
   *      dropped to avoid double-delivery; Deltas always pass through.
   *
   * Returns a [[SinkHandle]] whose `detach` flips a cancellation flag
   * (the stream stops on its next pull) and closes the sink.
   */
  def attach(viewer: ParticipantId,
             sink: SignalSink,
             resume: ResumeRequest = ResumeRequest.None,
             conversations: ConversationFilter = None): Task[SinkHandle] = Task {
    val cancelled = new AtomicBoolean(false)
    val boundary = new AtomicLong(Long.MinValue)
    val live: Stream[Signal] = sigil.signalsFor(viewer)

    val replayed: Stream[Signal] = replay(viewer, resume, conversations).evalTap { signal =>
      signal match {
        case e: Event =>
          val ts = e.timestamp.value
          val cur = boundary.get()
          if (ts > cur) boundary.compareAndSet(cur, ts)
          sink.push(e)
        case other =>
          sink.push(other)
      }
    }

    val forwarded: Stream[Signal] = live.filter {
      case e: Event => e.timestamp.value > boundary.get()
      case _: Delta => true
    }.evalTap(s => sink.push(s))

    val combined = (replayed ++ forwarded).takeWhile(_ => !cancelled.get())
    combined.drain.startUnit()

    new SinkHandle {
      override def detach: Task[Unit] =
        Task { cancelled.set(true) }.flatMap(_ => sink.close)
    }
  }

  /**
   * Resume-only — drains historical events for inspection without
   * touching the live stream. Useful for SSE handlers that want to
   * reply with a chunk of history and let the client reconnect for
   * live updates separately.
   *
   * Each replayed signal is run through `viewerTransforms`. Events
   * whose transforms drop them are filtered out.
   */
  def replay(viewer: ParticipantId,
             resume: ResumeRequest,
             conversations: ConversationFilter = None): Stream[Signal] = resume match {
    case ResumeRequest.None => Stream.empty
    case _                  => Stream.force(loadReplay(viewer, resume, conversations))
  }

  private def loadReplay(viewer: ParticipantId,
                         resume: ResumeRequest,
                         convFilter: ConversationFilter): Task[Stream[Signal]] =
    sigil.withDB(_.events.transaction(_.list)).map { all =>
      val scoped: List[Event] = convFilter match {
        case Some(cs) => all.filter(e => cs.contains(e.conversationId))
        case None     => all
      }
      // Apply visibility BEFORE the RecentMessages walk so the count reflects
      // what the viewer actually receives — otherwise an Agents-only message
      // would consume budget despite being filtered.
      val visible: List[Event] = scoped.filter(e => sigil.canSee(e, viewer))
      val selected: List[Event] = resume match {
        case ResumeRequest.None =>
          Nil
        case ResumeRequest.After(cursor) =>
          visible.filter(_.timestamp.value > cursor).sortBy(_.timestamp.value)
        case ResumeRequest.RecentMessages(max) if max <= 0 =>
          Nil
        case ResumeRequest.RecentMessages(max) =>
          // Walk newest-first, accumulate, stop after the `max`th Message.
          // The non-Message events trailing the cutoff Message are already
          // included by virtue of being newer than (or equal to) it.
          val desc = visible.sortBy(-_.timestamp.value)
          val acc = scala.collection.mutable.ListBuffer.empty[Event]
          var msgCount = 0
          val it = desc.iterator
          while (it.hasNext && msgCount < max) {
            val e = it.next()
            acc += e
            if (e.isInstanceOf[Message]) msgCount += 1
          }
          acc.toList.reverse
      }
      val transformed: List[Signal] = selected.map(e => sigil.applyViewerTransforms(e, viewer))
      Stream.emits(transformed)
    }
}

/**
 * Handle returned by [[SignalTransport.attach]]. Calling `detach`
 * stops further delivery (the running stream exits on its next pull)
 * and closes the underlying sink. Idempotent.
 */
trait SinkHandle {
  def detach: Task[Unit]
}
