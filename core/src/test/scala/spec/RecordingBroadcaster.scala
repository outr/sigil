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
 * Backed by a background fiber that drains `sigil.signals`. The fiber
 * exits naturally when `Sigil.shutdown` closes the underlying
 * SignalHub, or when the JVM fork ends — no running-flag plumbing
 * needed.
 */
final class RecordingBroadcaster {
  private val buf = new ConcurrentLinkedQueue[Signal]()

  /** Start draining `sigil.signals` into the internal buffer. */
  def attach(sigil: Sigil): Unit = {
    sigil.signals
      .evalMap(sig => Task { buf.add(sig); () })
      .drain
      .startUnit()
    ()
  }

  def recorded: List[Signal] = {
    import scala.jdk.CollectionConverters.*
    buf.iterator().asScala.toList
  }

  def clear(): Unit = buf.clear()
}
