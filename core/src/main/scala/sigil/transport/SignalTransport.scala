package sigil.transport

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.participant.ParticipantId
import sigil.signal.{Delta, Notice, Signal}

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

    // Latest-status replay for registered services — every fresh
    // subscriber sees the current state of every registered
    // [[sigil.service.Service]] before live signals start flowing,
    // so chips paint with current state without waiting for the next
    // state transition. Cheap (one map read + one push per service).
    val serviceStatuses: Stream[Signal] =
      Stream.emits(sigil.serviceStatusReplay).evalTap(s => sink.push(s))

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
      case e: Event  => e.timestamp.value > boundary.get()
      case _: Delta  => true
      case _: Notice => true
    }.evalTap(s => sink.push(s))

    val combined = (serviceStatuses ++ replayed ++ forwarded).takeWhile(_ => !cancelled.get())
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

  /**
   * Optional conversation-scope filter for the indexed event query.
   * `Some(cs)` with a non-empty set narrows to the `conversationId`
   * index via an `in` clause; an empty set matches nothing; `None`
   * spans every conversation.
   */
  private def conversationScopeFilter(convFilter: ConversationFilter): Option[Event.type => lightdb.filter.Filter[Event]] = {
    import lightdb.filter.*
    convFilter match {
      case Some(cs) if cs.isEmpty => Some(_ => Filter.In(Event.conversationId.name, Seq.empty[String]))
      case Some(cs)               => Some(_ => Event.conversationId.in(cs.toSeq.map(_.value)))
      case None                   => None
    }
  }

  private def loadReplay(viewer: ParticipantId,
                         resume: ResumeRequest,
                         convFilter: ConversationFilter): Task[Stream[Signal]] = {
    import lightdb.filter.*
    val scopeFilter = conversationScopeFilter(convFilter)
    resume match {
      case ResumeRequest.None =>
        Task.pure(Stream.empty)

      case ResumeRequest.RecentMessages(max) if max <= 0 =>
        Task.pure(Stream.empty)

      case ResumeRequest.After(cursor) =>
        // Indexed timestamp range, returned ascending — no in-memory sort.
        // Conversation scope (when present) ANDs into the same indexed query.
        sigil.withDB(_.events.transaction { tx =>
          val ranged = tx.query
            .filter(_ => Event.timestamp > cursor)
            .sort(lightdb.Sort.ByField(Event.timestamp, lightdb.SortDirection.Ascending))
          val scoped = scopeFilter.fold(ranged)(f => ranged.filter(f))
          scoped.toList
        }).map { ordered =>
          // Visibility is a per-viewer predicate, not an indexed field —
          // applied in memory over the already-narrowed, already-ordered set.
          val transformed: List[Signal] =
            ordered.filter(e => sigil.canSee(e, viewer)).map(e => sigil.applyViewerTransforms(e, viewer))
          Stream.emits(transformed)
        }

      case ResumeRequest.RecentMessages(max) =>
        // Walk newest-first off the indexed timestamp order, accumulate, stop
        // after the `max`th visible Message. The non-Message events trailing
        // the cutoff Message are already included by virtue of being newer
        // than (or equal to) it. The "is a Message" check is the polymorphic
        // Signal discriminator — not an indexed field — so it stays in memory,
        // but the descending stream lets the walk stop as soon as the budget
        // is met instead of materialising the whole store.
        sigil.withDB(_.events.transaction { tx =>
          val ordered = tx.query
            .sort(lightdb.Sort.ByField(Event.timestamp, lightdb.SortDirection.Descending))
          val scoped = scopeFilter.fold(ordered)(f => ordered.filter(f))
          // Apply visibility BEFORE the count walk so the budget reflects what
          // the viewer actually receives — an Agents-only message must not
          // consume a slot despite being filtered.
          var msgCount = 0
          scoped.stream
            .filter(e => sigil.canSee(e, viewer))
            .takeWhile { e =>
              if (msgCount < max) {
                if (e.isInstanceOf[Message]) msgCount += 1
                true
              } else false
            }
            .toList
        }).map { desc =>
          val transformed: List[Signal] =
            desc.reverse.map(e => sigil.applyViewerTransforms(e, viewer))
          Stream.emits(transformed)
        }
    }
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
