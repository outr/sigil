package spec

import fabric.*
import fabric.io.JsonFormatter
import org.scalatest.{Args, Status, SucceededStatus, Suite}
import spice.http.{HttpMethod, HttpRequest}
import spice.http.content.StringContent
import spice.net.*

/**
 * Live-spec gating for OpenRouter. Two layers of skip:
 *   1. [[LiveProbe.requireLiveEnabled]] — `SIGIL_LIVE=1` opt-in.
 *   2. `OPEN_ROUTER_TOKEN` presence + a minimal chat-completions
 *      probe. OpenRouter's `/api/v1/models` returns 200 for any
 *      caller, so the only reliable "is this key usable" check is
 *      a 1-token completion. 401/402/403 cancels the suite cleanly.
 */
object OpenRouterLiveSupport {
  def apiKey: Option[String] = sys.env.get("OPEN_ROUTER_TOKEN").filter(_.nonEmpty)

  /**
   * Cheap, broadly-stable model for the credential probe. This is
   * deliberately NOT the same as `OPENROUTER_TEST_MODEL` (Kimi-K2.6
   * by default per OpenRouterLiveSpec) — the probe tests AUTH, not
   * model behavior, so we want a model that doesn't have its own
   * reliability blips that would produce false-negative probe
   * failures. Overridable via env.
   */
  private val probeModel: String = sys.env.getOrElse("OPENROUTER_PROBE_MODEL", "openai/gpt-4o-mini")

  private def probe(key: String): HttpRequest = {
    val body = JsonFormatter.Compact(obj(
      "model" -> str(probeModel),
      "messages" -> arr(obj("role" -> str("user"), "content" -> str("hi"))),
      "max_tokens" -> num(1),
      "stream" -> bool(false)
    ))
    HttpRequest(
      method = HttpMethod.Post,
      url = url"https://openrouter.ai/api/v1/chat/completions",
      content = Some(StringContent(body, ContentType.`application/json`))
    ).withHeader("Authorization", s"Bearer $key")
  }

  def runGated(suite: Suite, testName: Option[String], args: Args)(runBody: => Status): Status =
    LiveProbe.requireLiveEnabled(suite).getOrElse {
      apiKey match {
        case None =>
          println(s"[skipped] ${suite.suiteName} — OPEN_ROUTER_TOKEN not set")
          SucceededStatus
        case Some(key) =>
          LiveProbe.runGatedProbe(suite, c => s"OPEN_ROUTER_TOKEN rejected by OpenRouter ($c)", probe(key))(runBody)
      }
    }
}
