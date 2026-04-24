package spec

import rapid.Task
import sigil.Sigil
import sigil.signal.Signal

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Test helper that captures every signal passing through `Sigil.signals`
 * into a concurrent queue. Tests subscribe by calling [[attach]] once,
 * then inspect [[recorded]] after `awaitIdle` (or equivalent) to assert
 * on what the framework emitted.
 *
 * Backed by a background fiber that drains `sigil.signals`; the fiber
 * is cancelled via [[detach]] (or it ends naturally when the test's JVM
 * fork exits).
 */
final class RecordingBroadcaster {
  private val buf = new ConcurrentLinkedQueue[Signal]()
  @volatile private var running: Boolean = true

  /** Start draining `sigil.signals` into the internal buffer. Returns
    * the fiber handle — callers typically ignore it and rely on JVM
    * shutdown to reap. */
  def attach(sigil: Sigil): Unit = {
    sigil.signals
      .evalMap(sig => Task { buf.add(sig); () })
      .takeWhile(_ => running)
      .drain
      .startUnit()
    ()
  }

  /** Stop draining. After this call the recorder no longer buffers
    * new signals, but [[recorded]] still returns what was captured. */
  def detach(): Unit = { running = false }

  def recorded: List[Signal] = {
    import scala.jdk.CollectionConverters.*
    buf.iterator().asScala.toList
  }

  def clear(): Unit = buf.clear()
}
