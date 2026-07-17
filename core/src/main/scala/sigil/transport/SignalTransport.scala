package sigil.transport

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.participant.ParticipantId
import sigil.signal.{Delta, Notice, Signal}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

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
    // The live-delivery scope is mutable: `subscribe` / `unsubscribe` on
    // the returned handle adjust it without reconnecting. Seeded from the
    // `conversations` arg (the conversation(s) the connection opens with).
    val filterRef = new AtomicReference[ConversationFilter](conversations)
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

    // Live forwarding honors the current per-connection scope. A signal
    // passes when the filter is unset (None — watch everything) or its
    // `conversationScope` is global (None) or in the subscribed set, or one
    // of its `additionalDeliveryScopes` is in the subscribed set (a #376
    // workflow-run lifecycle Event reaching its bound parent — #385).
    // Events additionally clear the replay boundary to avoid
    // double-delivery of anything replay already pushed.
    val forwarded: Stream[Signal] = live.filter { s =>
      val inScope = filterRef.get() match {
        case None => true
        case Some(cs) => s.conversationScope.forall(cs.contains) || s.additionalDeliveryScopes.exists(cs.contains)
      }
      inScope &&
      (s match {
        case e: Event => e.timestamp.value > boundary.get()
        case _ => true
      })
    }.evalTap(s => sink.push(s))

    val combined = (serviceStatuses ++ replayed ++ forwarded).takeWhile(_ => !cancelled.get())
    combined.drain.startUnit()

    new SinkHandle {
      override def detach: Task[Unit] =
        Task(cancelled.set(true)).flatMap(_ => sink.close)

      override def subscribe(conversationId: Id[Conversation]): Task[Unit] = Task {
        filterRef.updateAndGet(cur =>
          cur match {
            case Some(cs) => Some(cs + conversationId)
            case None => None // already unscoped — every conversation already delivered
          })
        ()
      }

      override def unsubscribe(conversationId: Id[Conversation]): Task[Unit] = Task {
        filterRef.updateAndGet(cur =>
          cur match {
            case Some(cs) => Some(cs - conversationId)
            case None => None
          })
        ()
      }
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
    case _ => Stream.force(loadReplay(viewer, resume, conversations))
  }

  /**
   * Optional conversation-scope filter for the indexed event query.
   * `Some(cs)` with a non-empty set narrows to the `conversationId`
   * index via an `in` clause; an empty set matches nothing; `None`
   * spans every conversation. The set is the exact set the subscriber
   * declared — no inference (#334 replaced the former worker-descendant
   * auto-expansion with client-driven `subscribe` / `unsubscribe`).
   */
  private def conversationScopeFilter(convFilter: ConversationFilter): Option[Event.type => lightdb.filter.Filter[Event]] = {
    import lightdb.filter.*
    convFilter match {
      case Some(cs) if cs.isEmpty => Some(_ => Filter.In(Event.conversationId.name, Seq.empty[String]))
      case Some(cs) => Some(_ => Event.conversationId.in(cs.toSeq.map(_.value)))
      case None => None
    }
  }

  /**
   * The single conversation id of `convFilter`, when it scopes to
   * exactly one. `eventsFor` is a single-conversation read; the
   * common per-conversation channel resolves here and routes through
   * it. A `None` filter or a multi-conversation set has no single
   * id and falls back to the cross-conversation indexed query.
   */
  private def singleConversation(convFilter: ConversationFilter): Option[Id[Conversation]] =
    convFilter match {
      case Some(cs) if cs.sizeIs == 1 => Some(cs.head)
      case _ => None
    }

  /**
   * Apply visibility + viewer transforms to a replay batch.
   */
  private def viewerScoped(events: List[Event], viewer: ParticipantId): List[Signal] =
    events.filter(e => sigil.canSee(e, viewer)).map(e => sigil.applyViewerTransforms(e, viewer))

  private def loadReplay(viewer: ParticipantId,
                         resume: ResumeRequest,
                         convFilter: ConversationFilter): Task[Stream[Signal]] = {
    val scopeFilter = conversationScopeFilter(convFilter)
    resume match {
      case ResumeRequest.None =>
        Task.pure(Stream.empty)

      case ResumeRequest.RecentMessages(max) if max <= 0 =>
        Task.pure(Stream.empty)

      case ResumeRequest.After(cursor) =>
        singleConversation(convFilter) match {
          case Some(convId) =>
            // Per-conversation resume cursor: the canonical paged read
            // with an exclusive lower timestamp bound and no message
            // cap returns the whole post-cursor window, oldest-first.
            sigil.eventsFor(convId, maxMessages = None, minTimestamp = Some(lightdb.time.Timestamp(cursor)))
              .map(page => Stream.emits(viewerScoped(page.events, viewer)))
          case None =>
            // Cross-conversation (or unscoped) resume: a plain indexed
            // timestamp range, returned ascending — `eventsFor` is a
            // single-conversation read and does not model this case.
            sigil.withDB(_.events.transaction { tx =>
              val ranged = tx.query
                .filter(_ => Event.timestamp > cursor)
                .sort(lightdb.Sort.ByField(Event.timestamp, lightdb.SortDirection.Ascending))
              val scoped = scopeFilter.fold(ranged)(f => ranged.filter(f))
              scoped.toList
            }).map(ordered => Stream.emits(viewerScoped(ordered, viewer)))
        }

      case ResumeRequest.RecentMessages(max) =>
        singleConversation(convFilter) match {
          case Some(convId) =>
            // Per-conversation recent-messages window: `eventsFor`'s
            // page 0 IS the message-counting walk — most-recent `max`
            // Messages plus every interleaved non-Message event.
            sigil.eventsFor(convId, page = 0, maxMessages = Some(max))
              .map(page => Stream.emits(viewerScoped(page.events, viewer)))
          case None =>
            // Cross-conversation recent-messages: walk newest-first off
            // the indexed timestamp order, stop after the `max`th
            // visible Message. `eventsFor` is single-conversation; this
            // global walk has no single-id mapping.
            sigil.withDB(_.events.transaction { tx =>
              val ordered = tx.query
                .sort(lightdb.Sort.ByField(Event.timestamp, lightdb.SortDirection.Descending))
              val scoped = scopeFilter.fold(ordered)(f => ordered.filter(f))
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
              Stream.emits(desc.reverse.map(e => sigil.applyViewerTransforms(e, viewer)))
            }
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

  /**
   * Add `conversationId` to this connection's live-delivery scope
   * without reconnecting — the client-driven counterpart to opening a
   * view (a tab, a worker drill-in). Subsequent live signals for that
   * conversation flow to the sink. No-op on an unscoped sink (a `None`
   * filter already delivers every conversation). Live-only: initial
   * history for a freshly-subscribed conversation is fetched separately
   * via the replay APIs (`eventsFor` / `RequestConversationHistory`),
   * so there's no replay/live double-delivery to coordinate.
   */
  def subscribe(conversationId: Id[Conversation]): Task[Unit] = Task.unit

  /**
   * Remove `conversationId` from the live-delivery scope (the view
   * closed). No-op on an unscoped sink.
   */
  def unsubscribe(conversationId: Id[Conversation]): Task[Unit] = Task.unit
}
