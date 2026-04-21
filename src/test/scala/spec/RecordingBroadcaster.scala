package spec

import rapid.Task
import sigil.SignalBroadcaster
import sigil.signal.Signal

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Test [[SignalBroadcaster]] that captures every signal it sees into a
 * concurrent queue. Tests inspect `recorded` after `awaitIdle` to assert on
 * what the framework pushed to wire.
 */
final class RecordingBroadcaster extends SignalBroadcaster {
  private val buf = new ConcurrentLinkedQueue[Signal]()

  override def handle(signal: Signal): Task[Unit] = Task {
    buf.add(signal)
    ()
  }

  def recorded: List[Signal] = {
    import scala.jdk.CollectionConverters.*
    buf.iterator().asScala.toList
  }

  def clear(): Unit = buf.clear()
}
