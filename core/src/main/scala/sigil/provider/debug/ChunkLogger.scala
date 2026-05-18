package sigil.provider.debug

import fabric.*
import fabric.io.JsonFormatter
import lightdb.time.Timestamp

import java.nio.file.{Files, OpenOption, Path, StandardOpenOption}

/**
 * Per-chunk diagnostic hook for streaming SSE provider responses.
 * Fires on every chunk the wire-line stream emits and at stream
 * termination with summary stats. Default [[ChunkLogger.NoOp]] does
 * nothing; apps opt in by wiring [[FileChunkLogger]] (or a custom
 * impl) via [[sigil.Sigil.chunkLogger]].
 *
 * Sigil bug #194 — `JsonLinesInterceptor` logs the aggregated
 * response after the stream completes. For SSE responses that hang
 * mid-stream (upstream timeout, slow inference, network stall) the
 * "response" line lands minutes after the request fired with no
 * inter-chunk timing visible. This hook surfaces the per-chunk
 * arrival data needed to diagnose stalls — "where did the stream
 * stall?", "did inter-chunk gap cross upstream's idle-timeout
 * threshold?", "is provider X consistently slow?".
 */
trait ChunkLogger {

  /**
   * Called for each SSE data line received from the stream.
   */
  def chunk(requestId: String,
            url: String,
            chunkIndex: Int,
            elapsedSinceRequestMs: Long,
            elapsedSincePrevChunkMs: Long,
            byteSize: Int,
            preview: String): Unit

  /**
   * Called once at stream termination — success or error — with
   * accumulated stats. `terminatedBy` is one of `"clean"` (stream
   * emitted [DONE] / completed normally) or `"error"` (stream
   * raised mid-flight).
   */
  def streamEnd(requestId: String,
                url: String,
                totalChunks: Int,
                totalDurationMs: Long,
                longestInterChunkGapMs: Long,
                longestGapAtChunkIndex: Int,
                terminatedBy: String): Unit
}

object ChunkLogger {

  val NoOp: ChunkLogger = new ChunkLogger {
    override def chunk(requestId: String,
                       url: String,
                       chunkIndex: Int,
                       elapsedSinceRequestMs: Long,
                       elapsedSincePrevChunkMs: Long,
                       byteSize: Int,
                       preview: String): Unit = ()
    override def streamEnd(requestId: String,
                           url: String,
                           totalChunks: Int,
                           totalDurationMs: Long,
                           longestInterChunkGapMs: Long,
                           longestGapAtChunkIndex: Int,
                           terminatedBy: String): Unit = ()
  }
}

/**
 * Writes per-chunk + stream-end JSON lines to a separate file from
 * the main wire log so high-volume per-chunk traffic doesn't bloat
 * the request/response forensic file. Preview field truncated to
 * `previewBytes` chars (default 200; set to 0 to disable previews
 * entirely for prompts with sensitive payloads).
 */
case class FileChunkLogger(path: Path, previewBytes: Int = 200) extends ChunkLogger {
  private val parent = Option(path.getParent)
  private val writeOpts: Array[OpenOption] = Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND)

  override def chunk(requestId: String,
                     url: String,
                     chunkIndex: Int,
                     elapsedSinceRequestMs: Long,
                     elapsedSincePrevChunkMs: Long,
                     byteSize: Int,
                     preview: String): Unit = {
    val truncated =
      if (previewBytes <= 0) ""
      else if (preview.length <= previewBytes) preview
      else preview.take(previewBytes)
    appendLine(obj(
      "kind" -> str("chunk"),
      "ts" -> str(Timestamp().toString),
      "requestId" -> str(requestId),
      "url" -> str(url),
      "chunkIndex" -> num(chunkIndex),
      "elapsedSinceRequestMs" -> num(elapsedSinceRequestMs),
      "elapsedSincePrevChunkMs" -> num(elapsedSincePrevChunkMs),
      "byteSize" -> num(byteSize),
      "preview" -> str(truncated)
    ))
  }

  override def streamEnd(requestId: String,
                         url: String,
                         totalChunks: Int,
                         totalDurationMs: Long,
                         longestInterChunkGapMs: Long,
                         longestGapAtChunkIndex: Int,
                         terminatedBy: String): Unit =
    appendLine(obj(
      "kind" -> str("stream-end"),
      "ts" -> str(Timestamp().toString),
      "requestId" -> str(requestId),
      "url" -> str(url),
      "totalChunks" -> num(totalChunks),
      "totalDurationMs" -> num(totalDurationMs),
      "longestInterChunkGapMs" -> num(longestInterChunkGapMs),
      "longestGapAtChunkIndex" -> num(longestGapAtChunkIndex),
      "terminatedBy" -> str(terminatedBy)
    ))

  private def appendLine(line: Json): Unit = synchronized {
    parent.foreach(p => if (!Files.exists(p)) Files.createDirectories(p))
    val serialized = JsonFormatter.Compact(line) + "\n"
    Files.writeString(path, serialized, writeOpts*)
  }
}
