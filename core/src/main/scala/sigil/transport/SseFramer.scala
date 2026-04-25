package sigil.transport

import fabric.io.JsonFormatter
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.event.Event
import sigil.signal.Signal

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Server-Sent Events framing helper. Turns a `Stream[Signal]` into a
 * `Stream[String]` of SSE-formatted frames suitable for piping into
 * an HTTP response body.
 *
 * Frame format: `id: <epoch-millis>\ndata: <json>\n\n`. The `id:`
 * line is emitted only for Events (whose `timestamp` provides a
 * resume cursor); Deltas have no stable cursor and emit just the
 * `data:` line. Heartbeat (`:hb\n\n`) frames are not emitted by
 * `frame` itself — apps that need keep-alives interleave them at the
 * HTTP layer (the rapid Stream API leaves time-driven events outside
 * the stream contract).
 */
object SseFramer {

  /**
   * Configuration for [[frame]].
   *
   * @param idFor override how the SSE `id:` line is produced. Default
   *              uses `Event.timestamp.toEpochMillis` for resume
   *              cursors; non-Event signals emit no `id:` line.
   */
  case class Config(idFor: Signal => Option[String] = SseFramer.defaultIdFor)

  /** Heartbeat frame (`:hb\n\n`) that apps interleave at the HTTP
    * layer to keep idle connections open through proxies. */
  val Heartbeat: String = ":hb\n\n"

  def defaultIdFor(signal: Signal): Option[String] = signal match {
    case e: Event => Some(e.timestamp.value.toString)
    case _        => None
  }

  /** Turn a stream of signals into a stream of SSE frame strings. */
  def frame(signals: Stream[Signal], config: Config = Config()): Stream[String] =
    signals.map(s => formatFrame(s, config))

  /** Render a single signal as one SSE frame. */
  def formatFrame(signal: Signal, config: Config = Config()): String = {
    val json = JsonFormatter.Compact(signal.json(using summon[RW[Signal]]))
    val sb = new StringBuilder
    config.idFor(signal).foreach(id => sb.append("id: ").append(id).append('\n'))
    sb.append("data: ").append(json).append("\n\n").toString
  }

  /**
   * Build a [[SignalSink]] that writes each signal as an SSE frame
   * via the supplied write callback. Apps with an HTTP response
   * `String => Task[Unit]` write handle plug it in here; closing
   * the sink doesn't close the response (apps own that lifecycle).
   */
  def sink(write: String => Task[Unit], config: Config = Config()): SignalSink =
    new SignalSink {
      override def push(signal: Signal): Task[Unit] = write(formatFrame(signal, config))
      override def close: Task[Unit] = Task.unit
    }
}
