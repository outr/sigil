package spec

import org.scalatest.{Args, Status, SucceededStatus, Suite}
import spice.http.{HttpMethod, HttpRequest}
import spice.net.*

/**
 * Shared gating for specs that require a live OpenAI API key. When
 * `OPENAI_API_KEY` is missing, the suite skips cleanly. When present,
 * probes `/v1/models`; a 401/402/403 cancels the suite so an expired
 * or quota-drained key doesn't surface as cascading test failures.
 */
object OpenAILiveSupport {
  def apiKey: Option[String] = sys.env.get("OPENAI_API_KEY").filter(_.nonEmpty)

  private def probe(key: String): HttpRequest = HttpRequest(
    method = HttpMethod.Get,
    url = url"https://api.openai.com/v1/models"
  ).withHeader("Authorization", s"Bearer $key")

  def runGated(suite: Suite, testName: Option[String], args: Args)(runBody: => Status): Status =
    LiveProbe.requireLiveEnabled(suite).getOrElse {
      apiKey match {
        case None =>
          println(s"[skipped] ${suite.suiteName} — OPENAI_API_KEY not set")
          SucceededStatus
        case Some(key) =>
          LiveProbe.runGatedProbe(suite, c => s"OPENAI_API_KEY rejected by OpenAI ($c)", probe(key))(runBody)
      }
    }
}
