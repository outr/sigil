package spec

import org.scalatest.{Args, Status, SucceededStatus, Suite}
import sigil.provider.anthropic.Anthropic
import spice.http.{HttpMethod, HttpRequest}
import spice.net.*

/**
 * Suite-level gating for specs that require a live Anthropic API key.
 * Reads `ANTHROPIC_API_KEY`; when absent, prints a single skip
 * message and returns [[SucceededStatus]]. When present, probes
 * `/v1/models` — a 401/402/403 response cancels the suite cleanly.
 */
object AnthropicLiveSupport {
  def apiKey: Option[String] = sys.env.get("ANTHROPIC_API_KEY").filter(_.nonEmpty)

  private def probe(key: String): HttpRequest = HttpRequest(
    method = HttpMethod.Get,
    url = url"https://api.anthropic.com/v1/models"
  )
    .withHeader("x-api-key", key)
    .withHeader("anthropic-version", Anthropic.ApiVersion)

  def runGated(suite: Suite, testName: Option[String], args: Args)(runBody: => Status): Status =
    LiveProbe.requireLiveEnabled(suite).getOrElse {
      apiKey match {
        case None =>
          println(s"[skipped] ${suite.suiteName} — ANTHROPIC_API_KEY not set")
          SucceededStatus
        case Some(key) =>
          LiveProbe.runGatedProbe(suite, c => s"ANTHROPIC_API_KEY rejected by Anthropic ($c)", probe(key))(runBody)
      }
    }
}
