package sigil.pipeline

import rapid.{Stream, Task}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.signal.Signal

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue}

/**
 * Multicast dispatcher for Signals. Every signal published via
 * `Sigil.publish` is emitted through a single [[SignalHub]]; each
 * subscriber gets its own bounded queue, so a slow subscriber sheds
 * signals on overflow without blocking peers.
 *
 * Late subscribers do not see historical signals — the hub is not a
 * replay log. Durable history lives in the events store; subscribers
 * that need it tail the store directly.
 *
 * **Overflow policy.** Per-subscriber bounded queue
 * (`subscriberCapacity`, default [[SignalHub.DefaultCapacity]]). The
 * hub never blocks the publisher on a slow subscriber — blocking a
 * synchronous multicast on its slowest consumer would stall the agent
 * turn and every other subscriber. Instead, when a queue is full, the
 * hub sheds the **oldest transient signal** — a `Delta` or `Notice`,
 * losing streaming granularity but not a discrete record — to make
 * room. A durable `Event` is dropped only when the queue holds
 * *nothing but* Events; that case logs at error level because live
 * subscribers miss the Event (it remains in `SigilDB.events`
 * regardless). Apps running high-volume reasoning models raise
 * [[sigil.Sigil.signalHubCapacity]].
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
final class SignalHub(subscriberCapacity: Int = SignalHub.DefaultCapacity) {
  private case class Subscriber(viewer: Option[ParticipantId],
                                queue: LinkedBlockingQueue[Option[Signal]])

  private val subscribers = new ConcurrentLinkedQueue[Subscriber]()
  private val closed = new AtomicBoolean(false)

  /**
   * Emit a signal to every active subscriber. Non-blocking; sheds the
   * oldest transient (or, only as a last resort, the oldest Event) on
   * per-subscriber overflow. No-op once [[close]] has been called.
   */
  def emit(signal: Signal): Unit = {
    if (closed.get()) return
    import scala.jdk.CollectionConverters.*
    subscribers.iterator().asScala.foreach(s => offerOrShed(s.queue, signal))
  }

  /**
   * Emit a signal only to subscribers whose registered viewer matches.
   * Used by `Sigil.publishTo(viewer, signal)` to single-target a
   * Notice (snapshot, reply, etc.) at one connected viewer.
   * Subscribers that registered with `viewer = None` (e.g. internal
   * `Sigil.signals` consumers) do NOT receive emitTo signals — they
   * only see broadcasts.
   */
  def emitTo(viewer: ParticipantId, signal: Signal): Unit = {
    if (closed.get()) return
    import scala.jdk.CollectionConverters.*
    subscribers.iterator().asScala.foreach { s =>
      if (s.viewer.contains(viewer)) offerOrShed(s.queue, signal)
    }
  }

  /**
   * Enqueue `signal` on `q`, making room on overflow by shedding the
   * oldest transient signal in preference to a durable `Event`. The
   * common case (queue has room) is lock-free; only the overflow
   * branch synchronizes, so concurrent overflow handlers on the same
   * queue don't fight.
   */
  private def offerOrShed(q: LinkedBlockingQueue[Option[Signal]], signal: Signal): Unit = {
    if (q.offer(Some(signal))) return
    q.synchronized {
      while (!q.offer(Some(signal)))
        firstTransient(q) match {
          case Some(victim) =>
            q.remove(victim)
            scribe.warn(
              s"SignalHub subscriber queue full (capacity=$subscriberCapacity); shed the oldest " +
                "transient signal — raise Sigil.signalHubCapacity if this recurs"
            )
          case None =>
            q.poll() // queue holds only Events — the oldest is unavoidably lost
            scribe.error(
              s"SignalHub subscriber queue full of Events (capacity=$subscriberCapacity); dropped " +
                "the oldest Event from the live stream (it remains durable in SigilDB.events)"
            )
        }
    }
  }

  /**
   * The oldest queued transient signal (a `Delta` / `Notice` — i.e.
   * anything that is not a durable [[Event]]) in FIFO order, or `None`
   * when the queue holds only Events. The returned value is the queue
   * element itself so the caller can `q.remove` it directly.
   */
  private def firstTransient(q: LinkedBlockingQueue[Option[Signal]]): Option[Option[Signal]] = {
    import scala.jdk.CollectionConverters.*
    q.iterator().asScala.collectFirst {
      case elem @ Some(s) if !s.isInstanceOf[Event] => elem
    }
  }

  /**
   * Close the hub: subsequent [[emit]] calls are no-ops, and every
   * active subscriber's stream completes (the next pull returns
   * `None`, which the stream interprets as natural end). Idempotent.
   */
  def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      import scala.jdk.CollectionConverters.*
      subscribers.iterator().asScala.foreach { s =>
        if (!s.queue.offer(None)) {
          s.queue.poll()
          s.queue.offer(None)
        }
      }
    }

  /**
   * New broadcast subscription — sees every Signal emitted via
   * [[emit]]. Does NOT receive [[emitTo]] signals targeted at a
   * specific viewer. Used by app-internal consumers (audit log,
   * recording broadcaster) that want the full firehose.
   */
  def subscribe: Stream[Signal] = subscribeInternal(viewer = None)

  /**
   * New viewer-scoped subscription — sees broadcasts AND
   * [[emitTo]] signals targeted at this viewer. Used by per-client
   * wire transports (DurableSocket sink via SignalTransport.attach).
   */
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
            case None => None // close sentinel — terminate stream
          }
        })(_ => Task { subscribers.remove(sub); () })
  }

  /**
   * Current subscriber count (for diagnostics / tests).
   */
  def subscriberCount: Int = subscribers.size()

  /**
   * Whether [[close]] has been called.
   */
  def isClosed: Boolean = closed.get()
}

object SignalHub {

  /**
   * Default per-subscriber queue capacity. Sized for always-on
   * reasoning-model streams — a single chain-of-thought turn emits
   * thousands of `ThinkingChunk` / delta signals, and a briefly-slow
   * subscriber (a slow socket write, a disk flush) must not overflow
   * on ordinary load. Apps tune via [[sigil.Sigil.signalHubCapacity]].
   */
  val DefaultCapacity: Int = 16384
}
