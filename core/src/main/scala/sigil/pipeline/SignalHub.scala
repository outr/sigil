package sigil.pipeline

import rapid.{Stream, Task}
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
 * **Lifecycle.** [[close]] terminates every active subscription
 * cleanly — each subscriber's stream completes (no error, no
 * indefinite block on `queue.take()`). `Sigil.shutdown` calls
 * `close` so app-side fibers consuming `sigil.signals` exit
 * naturally without needing their own running-flag state machine.
 * After close, [[emit]] is a no-op.
 */
final class SignalHub(subscriberCapacity: Int = 1024) {
  // Subscriber queues hold `Option[Signal]` — `None` is the close
  // sentinel that terminates the consumer's stream.
  private val subscribers = new ConcurrentLinkedQueue[LinkedBlockingQueue[Option[Signal]]]()
  private val closed = new AtomicBoolean(false)

  /** Emit a signal to every active subscriber. Non-blocking; drops
    * oldest + warns on per-subscriber overflow. No-op once [[close]]
    * has been called. */
  def emit(signal: Signal): Unit = {
    if (closed.get()) return
    import scala.jdk.CollectionConverters.*
    subscribers.iterator().asScala.foreach { q =>
      if (!q.offer(Some(signal))) {
        q.poll() // drop oldest
        q.offer(Some(signal))
        scribe.warn(
          s"SignalHub subscriber queue full (capacity=$subscriberCapacity); dropping oldest"
        )
      }
    }
  }

  /** Close the hub: subsequent [[emit]] calls are no-ops, and every
    * active subscriber's stream completes (the next pull returns
    * `None`, which the stream interprets as natural end). Idempotent
    * — calling close twice has no further effect. */
  def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      import scala.jdk.CollectionConverters.*
      subscribers.iterator().asScala.foreach { q =>
        // Best-effort offer of the close sentinel. If the queue is
        // already full, drop the oldest and try again — close must
        // win against a backlog or the subscriber would never see it.
        if (!q.offer(None)) {
          q.poll()
          q.offer(None)
        }
      }
    }
  }

  /** New subscription: returns a [[Stream]] that emits every signal
    * published after subscription, completing naturally when the hub
    * is closed via [[close]].
    *
    * **Eager registration.** The underlying queue is registered with
    * the hub *synchronously* when `subscribe` is called, BEFORE the
    * stream value is constructed. Any [[emit]] that happens between
    * `subscribe` returning and the consumer's first pull is queued
    * for the consumer (rather than dropped because no subscriber
    * existed yet). This closes the race where attach-then-publish
    * patterns (e.g. `SignalTransport.attach` followed by
    * `Sigil.publish`) lost early signals.
    *
    * **Consume-or-leak contract.** Callers MUST drain the returned
    * stream — the queue is registered immediately and stays
    * registered until the stream terminates (natural end via close
    * sentinel, error, or consumer short-circuit). A `subscribe` call
    * whose stream is constructed and then dropped without consumption
    * leaks one queue. Every framework-internal caller (Sigil.signals,
    * SignalTransport.attach, RecordingBroadcaster) drains; apps follow
    * the same pattern. */
  def subscribe: Stream[Signal] = {
    val q = new LinkedBlockingQueue[Option[Signal]](subscriberCapacity)
    subscribers.add(q) // EAGER — register before returning the stream value
    Stream
      .using(Task.pure(q))(qq =>
        Stream.unfoldStreamEval(qq) { queue =>
          Task(queue.take()).map {
            case Some(sig) => Some((Stream.emit(sig), queue))
            case None      => None // close sentinel — terminate stream
          }
        }
      )(queue => Task { subscribers.remove(queue); () })
  }

  /** Current subscriber count (for diagnostics / tests). */
  def subscriberCount: Int = subscribers.size()

  /** Whether [[close]] has been called. */
  def isClosed: Boolean = closed.get()
}
