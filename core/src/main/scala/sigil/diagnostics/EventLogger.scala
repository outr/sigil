package sigil.diagnostics

import fabric.*
import fabric.io.JsonFormatter
import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil
import sigil.participant.ParticipantId
import sigil.signal.Signal

import java.nio.file.{Files, OpenOption, Path, StandardOpenOption}

/**
 * Appends every signal on a viewer's stream to a JSON-lines log.
 *
 * Taps [[Sigil.signalsFor]] — the same `canSee`-filtered, viewer-
 * transformed stream the WS transport delivers to a connected client,
 * so the log is exactly what that client receives. Complements the
 * HTTP wire interceptor, which only captures provider round-trips:
 * progress checkpoints, stall interventions, in-process tool results,
 * mode changes, and user-authored messages never touch HTTP.
 *
 * Each append is `synchronized` and flushed immediately — a crashed
 * process keeps everything written so far. Per-signal write failures
 * are logged and skipped rather than tearing down the subscription.
 */
final class EventLogger(path: Path, viewer: ParticipantId):
  Option(path.getParent).foreach(p => if !Files.exists(p) then Files.createDirectories(p))

  private val writeOpts: Array[OpenOption] =
    Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND)

  /**
   * Subscribe the viewer's signal stream and append each signal.
   * Returns a `Task` that runs forever — start it as a background
   * fiber at boot.
   */
  def attach(sigil: Sigil): Task[Unit] =
    sigil.signalsFor(viewer).evalMap { signal =>
      Task {
        try appendSignal(signal)
        catch
          case t: Throwable =>
            scribe.warn(s"EventLogger: failed to append ${signal.getClass.getSimpleName}: ${t.getMessage}")
      }
    }.drain

  private def appendSignal(signal: Signal): Unit =
    val body =
      try Signal.rw.read(signal)
      catch
        case t: Throwable =>
          obj(
            "error" -> str(s"serialization failed: ${t.getMessage}"),
            "toString" -> str(signal.toString))
    appendLine(obj(
      "kind" -> str("event"),
      "ts" -> str(Timestamp().toString),
      "signal" -> str(signal.getClass.getSimpleName),
      "body" -> body
    ))

  private def appendLine(line: Json): Unit = synchronized {
    Files.writeString(path, JsonFormatter.Compact(line) + "\n", writeOpts*)
  }
