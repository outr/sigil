package sigil.provider.debug

import rapid.{Stream, Task}
import spice.http.{HttpRequest, HttpResponse, HttpStatus}
import spice.http.content.StringContent
import spice.net.ContentType
import spice.http.client.intercept.Interceptor

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success, Try}

/**
 * Helper that wraps a streaming-line response so the
 * [[Interceptor]]'s `after` callback fires exactly once with the
 * correct [[Try]] regardless of whether the underlying stream
 * completed normally or errored mid-flight.
 *
 * Without this, every provider's bare `onFinalize { wireInterceptor.after(req, Success(...)) }`
 * pattern records the partial body as a successful response when the
 * stream errors — masking real failures (HTTP 5xx mid-stream, network
 * drops, our own [[sigil.provider.RequestOverBudgetException]],
 * `streamTimeout` firing during a hung stream, etc.) from
 * post-mortem wire-log analysis.
 *
 * Usage:
 * {{{
 *   StreamWireInterceptor.attach(lines, sigilRef.wireInterceptor, intercepted) { rawLine =>
 *     // existing parser
 *     Stream.emits(parseLine(rawLine, state))
 *   }
 * }}}
 *
 * The helper is responsible for:
 *   1. Buffering the raw response lines as the stream emits them.
 *   2. Capturing the throwable if the stream raises mid-flight.
 *   3. Calling `wireInterceptor.after(request, Success(response))` on
 *      clean termination, OR `Failure(throwable)` on error path —
 *      whichever fires first wins (idempotent flag).
 */
object StreamWireInterceptor {

  /** Attach the wire-log capture + result-aware finalizer to a
    * line-stream pipeline. The transformer `f` is the provider's
    * existing `parseLine`-style logic; this helper stays neutral on
    * what each line means and just buffers + finalizes.
    *
    * `chunkLogger` (default [[ChunkLogger.NoOp]]) gets a per-line
    * pulse with arrival timing — used by [[FileChunkLogger]] for
    * post-hoc forensics on streaming responses that stall mid-flight
    * (sigil bug #194). Non-SSE-data lines (`""` separators, comments,
    * etc.) are skipped — only `data:`-prefixed lines count toward
    * the chunk index. Stream termination emits a final summary call
    * with the longest inter-chunk gap. */
  def attach[T](lines: Stream[String],
                interceptor: Interceptor,
                request: HttpRequest,
                chunkLogger: ChunkLogger = ChunkLogger.NoOp)
               (f: String => Stream[T]): Stream[T] = {
    val bodyBuf = new StringBuilder
    val errorRef: AtomicReference[Option[Throwable]] = new AtomicReference(None)
    val requestId = UUID.randomUUID().toString
    val url = request.url.toString
    val startNanos = System.nanoTime()
    var prevChunkNanos = startNanos
    var chunkIndex = 0
    var totalChunks = 0
    var longestGapMs: Long = 0L
    var longestGapAtIndex: Int = -1

    lines
      .onErrorFinalize { t =>
        Task { errorRef.compareAndSet(None, Some(t)); () }
      }
      .flatMap { line =>
        bodyBuf.append(line).append('\n')
        if (line.startsWith("data:")) {
          val nowNanos = System.nanoTime()
          val sinceRequestMs = (nowNanos - startNanos) / 1_000_000L
          val sincePrevMs    = (nowNanos - prevChunkNanos) / 1_000_000L
          if (sincePrevMs > longestGapMs) {
            longestGapMs = sincePrevMs
            longestGapAtIndex = chunkIndex
          }
          chunkLogger.chunk(
            requestId               = requestId,
            url                     = url,
            chunkIndex              = chunkIndex,
            elapsedSinceRequestMs   = sinceRequestMs,
            elapsedSincePrevChunkMs = sincePrevMs,
            byteSize                = line.length,
            preview                 = line
          )
          prevChunkNanos = nowNanos
          chunkIndex += 1
          totalChunks += 1
        }
        f(line)
      }
      .guarantee(Task.defer {
        // Use `guarantee` (chained into `pull.close`) instead of
        // `onFinalize` because rapid's onFinalize only wraps the
        // OUTER pull's stepTask — errors originating in a Concat'd
        // inner stream (Stream.append, Stream.flatMap, etc.) bypass
        // it. `drain`'s finally block always calls pull.close, so
        // guarantee fires on every termination path: clean Stop,
        // outer error, inner-Concat error, fiber cancellation.
        val totalMs = (System.nanoTime() - startNanos) / 1_000_000L
        val terminatedBy = errorRef.get() match {
          case Some(_) => "error"
          case None    => "clean"
        }
        chunkLogger.streamEnd(
          requestId              = requestId,
          url                    = url,
          totalChunks            = totalChunks,
          totalDurationMs        = totalMs,
          longestInterChunkGapMs = longestGapMs,
          longestGapAtChunkIndex = longestGapAtIndex,
          terminatedBy           = terminatedBy
        )
        val tryResponse: Try[HttpResponse] = errorRef.get() match {
          case Some(t) => Failure(t)
          case None    =>
            Success(HttpResponse(
              status = HttpStatus.OK,
              content = Some(StringContent(bodyBuf.toString, ContentType("text", "event-stream")))
            ))
        }
        interceptor.after(request, tryResponse).unit
      })
  }
}
