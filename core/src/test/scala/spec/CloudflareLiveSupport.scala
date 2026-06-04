package spec

import org.scalatest.{Args, Status, SucceededStatus, Suite}
import spice.http.{HttpMethod, HttpRequest}
import spice.net.*

/**
 * Shared gating for specs that require live Cloudflare Workers AI
 * credentials (`CLOUDFLARE_AUTH_TOKEN` + `CLOUDFLARE_ACCOUNT_ID`).
 * Mirrors [[GoogleLiveSupport]] / [[AnthropicLiveSupport]] /
 * [[OpenRouterLiveSupport]] so every live-API suite uses the same
 * gate convention — SIGIL_LIVE opt-in + credential probe.
 *
 * Probes the account's Workers AI model-listing endpoint (a free
 * metadata call that doesn't consume neurons). A 401 / 402 / 403 /
 * 429 cancels the suite — covers unauthorized keys, payment-
 * required accounts, forbidden access, and the daily-neuron quota
 * wall — so a drained quota doesn't cascade into N per-test
 * failures.
 */
object CloudflareLiveSupport {
  def apiToken: Option[String] = sys.env.get("CLOUDFLARE_AUTH_TOKEN").filter(_.nonEmpty)
  def accountId: Option[String] = sys.env.get("CLOUDFLARE_ACCOUNT_ID").filter(_.nonEmpty)

  private def probe(token: String, accountId: String): HttpRequest = HttpRequest(
    method = HttpMethod.Get,
    url = URL.parse(s"https://api.cloudflare.com/client/v4/accounts/$accountId/ai/models/search")
  ).withHeader("Authorization", s"Bearer $token")

  def runGated(suite: Suite, testName: Option[String], args: Args)(runBody: => Status): Status =
    LiveProbe.requireLiveEnabled(suite).getOrElse {
      (apiToken, accountId) match {
        case (None, _) | (_, None) =>
          println(s"[skipped] ${suite.suiteName} — CLOUDFLARE_AUTH_TOKEN / CLOUDFLARE_ACCOUNT_ID not set")
          SucceededStatus
        case (Some(token), Some(account)) =>
          LiveProbe.runGatedProbe(suite, c => s"Cloudflare credentials rejected ($c)", probe(token, account))(runBody)
      }
    }
}
