package spec

import org.scalatest.{Args, Status, SucceededStatus, Suite}
import sigil.provider.anthropic.Anthropic
import spice.http.{HttpMethod, HttpRequest}
import spice.http.client.HttpClient
import spice.http.content.StringContent
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

  /**
   * Like [[runGated]], but additionally probes that the SPECIFIC model is
   * available to this key. Entitlement-gated models (e.g. Claude Fable 5,
   * which needs Project Glasswing / a qualifying tier) return HTTP 404
   * "model not available" on a real request even though the key
   * authenticates fine against `/v1/models`. A 404 here cancels the suite
   * cleanly — same posture as a revoked key — so a conformance spec for a
   * model this account can't reach doesn't hard-fail the run. The model
   * slug is the id's trailing segment (the `anthropic/` namespace stripped).
   */
  def runGatedForModel(suite: Suite, testName: Option[String], args: Args, modelId: String)(runBody: => Status): Status =
    runGated(suite, testName, args) {
      apiKey match {
        case Some(key) =>
          val slug = modelId.split("/").last
          modelStatusCode(key, slug) match {
            case Some(404) =>
              println(s"[skipped] ${suite.suiteName} — model $slug not available for this ANTHROPIC_API_KEY (HTTP 404)")
              SucceededStatus
            case _ => runBody
          }
        case None => runBody
      }
    }

  private def messagesProbe(key: String, slug: String): HttpRequest = HttpRequest(
    method = HttpMethod.Post,
    url = url"https://api.anthropic.com/v1/messages",
    content = Some(StringContent(
      s"""{"model":"$slug","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}""",
      ContentType.`application/json`
    ))
  )
    .withHeader("x-api-key", key)
    .withHeader("anthropic-version", Anthropic.ApiVersion)

  private def modelStatusCode(key: String, slug: String): Option[Int] =
    scala.util.Try {
      HttpClient.modify(_ => messagesProbe(key, slug)).noFailOnHttpStatus.send().sync().status.code
    }.toOption
}
