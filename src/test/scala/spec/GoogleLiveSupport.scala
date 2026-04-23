package spec

import org.scalatest.{Args, Status, SucceededStatus, Suite}
import spice.http.{HttpMethod, HttpRequest}
import spice.net.*

/**
 * Shared gating for specs that require a live Google / Gemini API
 * key (either `GOOGLE_API_KEY` or `GEMINI_API_KEY`). When neither is
 * set, the suite skips cleanly. When present, probes
 * `/v1beta/models`; a 401/402/403 cancels the suite.
 */
object GoogleLiveSupport {
  def apiKey: Option[String] = sys.env.get("GOOGLE_API_KEY")
    .orElse(sys.env.get("GEMINI_API_KEY"))
    .filter(_.nonEmpty)

  private def probe(key: String): HttpRequest = HttpRequest(
    method = HttpMethod.Get,
    url = url"https://generativelanguage.googleapis.com/v1beta/models"
  ).withHeader("x-goog-api-key", key)

  def runGated(suite: Suite, testName: Option[String], args: Args)(runBody: => Status): Status =
    LiveProbe.requireLiveEnabled(suite).getOrElse {
      apiKey match {
        case None =>
          println(s"[skipped] ${suite.suiteName} — GOOGLE_API_KEY / GEMINI_API_KEY not set")
          SucceededStatus
        case Some(key) =>
          LiveProbe.runGatedProbe(suite, c => s"API key rejected by Google ($c)", probe(key))(runBody)
      }
    }
}
