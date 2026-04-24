package sigil.pipeline

import rapid.{Stream, Task}
import sigil.signal.Signal

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
 */
final class SignalHub(subscriberCapacity: Int = 1024) {
  private val subscribers = new ConcurrentLinkedQueue[LinkedBlockingQueue[Signal]]()

  /** Emit a signal to every active subscriber. Non-blocking; drops
    * oldest + warns on per-subscriber overflow. */
  def emit(signal: Signal): Unit = {
    import scala.jdk.CollectionConverters.*
    subscribers.iterator().asScala.foreach { q =>
      if (!q.offer(signal)) {
        q.poll() // drop oldest
        q.offer(signal)
        scribe.warn(
          s"SignalHub subscriber queue full (capacity=$subscriberCapacity); dropping oldest"
        )
      }
    }
  }

  /** New subscription: returns a [[Stream]] that emits every signal
    * published after subscription. The underlying queue is registered
    * on first pull and removed when the stream terminates (natural
    * end, error, or consumer short-circuit). */
  def subscribe: Stream[Signal] = {
    val q = new LinkedBlockingQueue[Signal](subscriberCapacity)
    // Register lazily on first pull (via `using`) so subscribers that are
    // constructed but never drained don't retain queues forever.
    Stream
      .using(Task { subscribers.add(q); q })(qq =>
        Stream.unfoldStreamEval(qq)(queue => Task(Some((Stream.emit(queue.take()), queue))))
      )(queue => Task { subscribers.remove(queue); () })
  }

  /** Current subscriber count (for diagnostics / tests). */
  def subscriberCount: Int = subscribers.size()
}
