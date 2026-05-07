package sigil.provider.llamacpp

import fabric.*
import fabric.rw.valueRW
import rapid.Task
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import spice.http.client.HttpClient
import spice.http.{HttpMethod, HttpRequest}
import spice.http.content.StringContent
import spice.net.{ContentType, URL}

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
 */
final case class LlamaCppTokenizer(baseUrl: URL,
                                   fallback: Tokenizer = HeuristicTokenizer) extends Tokenizer {

  override def count(text: String): Int =
    countRemote(text).handleError(_ => Task.pure(fallback.count(text))).sync()

  private def countRemote(text: String): Task[Int] = {
    val body = obj("content" -> str(text))
    val req = HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath("/tokenize"),
      content = Some(StringContent(fabric.io.JsonFormatter.Compact(body), ContentType.`application/json`))
    )
    HttpClient.modify(_ => req).call[Json].map { json =>
      json.get("tokens").map(_.asVector.size).getOrElse(fallback.count(text))
    }
  }
}
