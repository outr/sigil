package sigil.provider.llamacpp

import fabric.*
import fabric.rw.valueRW
import rapid.Task
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import spice.http.client.HttpClient
import spice.http.{HttpMethod, HttpRequest}
import spice.http.content.StringContent
import spice.net.{ContentType, URL}

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.duration.*

/**
 * Backend-exact tokenizer for llama.cpp. Calls the running server's
 * `POST /tokenize` endpoint with a JSON body `{"content": <text>}` and
 * counts the returned tokens.
 *
 * Bug #45 — `LlamaCppProvider` previously inherited the framework's
 * default [[HeuristicTokenizer]] (3.5 chars/token), which under-counts
 * JSON / tool-call / chat-template content by ~7-15%. The undercount
 * accumulated past pre-flight's gate so the wire-rendered request
 * exceeded `n_ctx` and the server rejected with 400. Calling the
 * backend's tokenizer eliminates the gap — every count is the exact
 * number of tokens the model will see.
 *
 * `/tokenize` failures (server unreachable, endpoint disabled on
 * older builds, transient errors) fall through to the
 * [[HeuristicTokenizer]] so a degraded backend doesn't crash the
 * pre-flight pass — the heuristic stays the safety net it's always
 * been.
 *
 * Bug #54 hardening:
 *   - **HTTP timeout** — hard cap (`requestTimeout`, default 5s) on
 *     each `/tokenize` round-trip so the call cannot block a fiber
 *     thread indefinitely if the backend stalls.
 *   - **In-memory cache** — text → token-count, bounded LRU. Per-turn
 *     curator + profiler iterations re-tokenize the same stable
 *     sections (instructions, mode block, framing prefix, repeated
 *     frames) many times in succession; caching collapses the
 *     repetition to one HTTP call per unique text. Cap at
 *     `cacheSize` (default 4096) entries.
 *   - **Circuit breaker** — after `breakerThreshold` consecutive
 *     failures (default 3) the tokenizer short-circuits to the
 *     fallback heuristic for `breakerCooldown` (default 30s),
 *     preventing one stalled backend from compounding into a
 *     wall-of-`.sync()` calls each waiting `requestTimeout`. Resets
 *     on the first successful round-trip after the cooldown.
 */
final case class LlamaCppTokenizer(baseUrl: URL,
                                   fallback: Tokenizer = HeuristicTokenizer,
                                   requestTimeout: FiniteDuration = 5.seconds,
                                   cacheSize: Int = 4096,
                                   breakerThreshold: Int = 3,
                                   breakerCooldown: FiniteDuration = 30.seconds) extends Tokenizer {

  private val cache: java.util.LinkedHashMap[String, Int] =
    new java.util.LinkedHashMap[String, Int](cacheSize, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, Int]): Boolean =
        size() > cacheSize
    }
  private val consecutiveFailures: AtomicInteger = new AtomicInteger(0)
  private val breakerOpenUntil: AtomicLong = new AtomicLong(0L)

  override def count(text: String): Int = {
    if (text.isEmpty) 0
    else if (breakerOpen) fallback.count(text)
    else {
      val cached = cache.synchronized(Option(cache.get(text)))
      cached match {
        case Some(n) => n
        case None =>
          val n = countRemote(text)
            .map { result =>
              consecutiveFailures.set(0)
              result
            }
            .handleError { _ =>
              if (consecutiveFailures.incrementAndGet() >= breakerThreshold) {
                breakerOpenUntil.set(System.currentTimeMillis() + breakerCooldown.toMillis)
                scribe.warn(
                  s"LlamaCppTokenizer circuit breaker tripped for $baseUrl — falling back to heuristic " +
                    s"for ${breakerCooldown.toSeconds}s"
                )
              }
              Task.pure(fallback.count(text))
            }
            .sync()
          cache.synchronized(cache.put(text, n))
          n
      }
    }
  }

  private def breakerOpen: Boolean = {
    val until = breakerOpenUntil.get()
    if (until == 0L) false
    else if (System.currentTimeMillis() < until) true
    else {
      breakerOpenUntil.set(0L)
      consecutiveFailures.set(0)
      false
    }
  }

  private def countRemote(text: String): Task[Int] = {
    val body = obj("content" -> str(text))
    val req = HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath("/tokenize"),
      content = Some(StringContent(fabric.io.JsonFormatter.Compact(body), ContentType.`application/json`))
    )
    HttpClient.modify(_ => req).timeout(requestTimeout).call[Json].map { json =>
      json.get("tokens").map(_.asVector.size).getOrElse(fallback.count(text))
    }
  }
}
