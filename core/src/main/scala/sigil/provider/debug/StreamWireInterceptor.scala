package sigil.provider.debug

import rapid.{Stream, Task}
import spice.http.{HttpRequest, HttpResponse, HttpStatus}
import spice.http.content.StringContent
import spice.net.ContentType
import spice.http.client.intercept.Interceptor

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
    * what each line means and just buffers + finalizes. */
  def attach[T](lines: Stream[String],
                interceptor: Interceptor,
                request: HttpRequest)
               (f: String => Stream[T]): Stream[T] = {
    val bodyBuf = new StringBuilder
    val errorRef: AtomicReference[Option[Throwable]] = new AtomicReference(None)

    lines
      .onErrorFinalize { t =>
        Task { errorRef.compareAndSet(None, Some(t)); () }
      }
      .flatMap { line =>
        bodyBuf.append(line).append('\n')
        f(line)
      }
      .onFinalize(Task.defer {
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
