package sigil.pipeline

import rapid.{Stream, Task}
import sigil.participant.ParticipantId
import sigil.signal.Signal

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue}

/**
 * Multicast dispatcher for Signals. Every signal published via
 * `Sigil.publish` is emitted through a single [[SignalHub]]; each
 * subscriber gets its own bounded queue, so a slow subscriber drops
 * oldest signals on overflow without blocking peers.
 *
 * Late subscribers do not see historical signals — the hub is not a
 * replay log. Durable history lives in the events store; subscribers
 * that need it tail the store directly.
 *
 * Overflow policy: per-subscriber bounded queue (`subscriberCapacity`,
 * default 1024). When full, the oldest signal is dropped to make
 * room for the new one and a warn is logged. Apps that need
 * guaranteed delivery should size the capacity appropriately or
 * consume faster.
 *
 * **Per-viewer routing.** Subscribers register an optional `viewer` at
 * subscribe time. [[emit]] broadcasts to every subscriber regardless
 * of viewer; [[emitTo]] delivers only to subscribers whose registered
 * viewer matches. This is how `Sigil.publishTo(viewer, signal)` reaches
 * a single connected client without going through `signalsFor` filtering.
 *
 * **Lifecycle.** [[close]] terminates every active subscription
 * cleanly — each subscriber's stream completes (no error, no
 * indefinite block on `queue.take()`). `Sigil.shutdown` calls
 * `close` so app-side fibers consuming `sigil.signals` exit
 * naturally without needing their own running-flag state machine.
 * After close, [[emit]] is a no-op.
 */
final class SignalHub(subscriberCapacity: Int = 1024) {
  private case class Subscriber(viewer: Option[ParticipantId],
                                queue: LinkedBlockingQueue[Option[Signal]])

  private val subscribers = new ConcurrentLinkedQueue[Subscriber]()
  private val closed = new AtomicBoolean(false)

  /** Emit a signal to every active subscriber. Non-blocking; drops
    * oldest + warns on per-subscriber overflow. No-op once [[close]]
    * has been called. */
  def emit(signal: Signal): Unit = {
    if (closed.get()) return
    import scala.jdk.CollectionConverters.*
    subscribers.iterator().asScala.foreach(s => offerOrDropOldest(s.queue, signal))
  }

  /** Emit a signal only to subscribers whose registered viewer matches.
    * Used by `Sigil.publishTo(viewer, signal)` to single-target a
    * Notice (snapshot, reply, etc.) at one connected viewer.
    * Subscribers that registered with `viewer = None` (e.g. internal
    * `Sigil.signals` consumers) do NOT receive emitTo signals — they
    * only see broadcasts. */
  def emitTo(viewer: ParticipantId, signal: Signal): Unit = {
    if (closed.get()) return
    import scala.jdk.CollectionConverters.*
    subscribers.iterator().asScala.foreach { s =>
      if (s.viewer.contains(viewer)) offerOrDropOldest(s.queue, signal)
    }
  }

  private def offerOrDropOldest(q: LinkedBlockingQueue[Option[Signal]], signal: Signal): Unit = {
    if (!q.offer(Some(signal))) {
      q.poll() // drop oldest
      q.offer(Some(signal))
      scribe.warn(
        s"SignalHub subscriber queue full (capacity=$subscriberCapacity); dropping oldest"
      )
    }
  }

  /** Close the hub: subsequent [[emit]] calls are no-ops, and every
    * active subscriber's stream completes (the next pull returns
    * `None`, which the stream interprets as natural end). Idempotent. */
  def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      import scala.jdk.CollectionConverters.*
      subscribers.iterator().asScala.foreach { s =>
        if (!s.queue.offer(None)) {
          s.queue.poll()
          s.queue.offer(None)
        }
      }
    }
  }

  /** New broadcast subscription — sees every Signal emitted via
    * [[emit]]. Does NOT receive [[emitTo]] signals targeted at a
    * specific viewer. Used by app-internal consumers (audit log,
    * recording broadcaster) that want the full firehose. */
  def subscribe: Stream[Signal] = subscribeInternal(viewer = None)

  /** New viewer-scoped subscription — sees broadcasts AND
    * [[emitTo]] signals targeted at this viewer. Used by per-client
    * wire transports (DurableSocket sink via SignalTransport.attach). */
  def subscribeFor(viewer: ParticipantId): Stream[Signal] =
    subscribeInternal(viewer = Some(viewer))

  private def subscribeInternal(viewer: Option[ParticipantId]): Stream[Signal] = {
    val q = new LinkedBlockingQueue[Option[Signal]](subscriberCapacity)
    val sub = Subscriber(viewer, q)
    subscribers.add(sub) // EAGER — register before returning the stream value
    Stream
      .using(Task.pure(q))(qq =>
        Stream.unfoldStreamEval(qq) { queue =>
          Task(queue.take()).map {
            case Some(sig) => Some((Stream.emit(sig), queue))
            case None      => None // close sentinel — terminate stream
          }
        }
      )(_ => Task { subscribers.remove(sub); () })
  }

  /** Current subscriber count (for diagnostics / tests). */
  def subscriberCount: Int = subscribers.size()

  /** Whether [[close]] has been called. */
  def isClosed: Boolean = closed.get()
}
