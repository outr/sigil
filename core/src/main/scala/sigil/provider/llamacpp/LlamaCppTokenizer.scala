package sigil.provider.llamacpp

import fabric.*
import fabric.rw.valueRW
import rapid.Task
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import spice.http.client.{HttpClient, RetryManager}
import spice.http.client.intercept.Interceptor
import spice.http.{HttpMethod, HttpRequest}
import spice.http.content.StringContent
import spice.net.{ContentType, URL}

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.duration.*

/**
 * Backend-exact tokenizer for llama.cpp. Calls the running server's
 * `POST /tokenize` endpoint with a JSON body `{"content": <text>}` and
 * counts the returned tokens.
 */
final case class LlamaCppTokenizer(baseUrl: URL,
                                   fallback: Tokenizer = HeuristicTokenizer,
                                   requestTimeout: FiniteDuration = 5.seconds,
                                   cacheSize: Int = 4096,
                                   breakerThreshold: Int = 3,
                                   breakerCooldown: FiniteDuration = 30.seconds,
                                   interceptor: Interceptor = Interceptor.empty)
  extends Tokenizer {

  private val cache: java.util.LinkedHashMap[String, Int] =
    new java.util.LinkedHashMap[String, Int](cacheSize, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, Int]): Boolean =
        size() > cacheSize
    }
  private val consecutiveFailures: AtomicInteger = new AtomicInteger(0)
  private val breakerOpenUntil: AtomicLong = new AtomicLong(0L)

  override def count(text: String): Int =
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
    ).withHeader("Connection", "close")
    HttpClient.modify(_ => req)
      .interceptor(interceptor)
      .timeout(requestTimeout)
      .retryManager(RetryManager.simple(retries = 1, delay = 100.millis, warnRetries = false))
      .call[Json].map { json =>
        json.get("tokens").map(_.asVector.size).getOrElse(fallback.count(text))
      }
  }
}
