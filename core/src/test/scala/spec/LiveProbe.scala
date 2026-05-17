package spec

import org.scalatest.{Status, SucceededStatus, Suite}
import spice.http.HttpRequest
import spice.http.client.HttpClient

/**
 * Shared probe logic for live-API-gated specs.
 *
 * Three layers of gating:
 *   1. The `SIGIL_LIVE=1` opt-in. Live suites are off by default, so
 *      `sbt test` runs only the free/local surface (LlamaCpp + unit
 *      specs). Set `SIGIL_LIVE=1` to exercise any paid provider.
 *   2. The `SIGIL_SLOW=1` opt-in for time-consuming specs (multi-minute
 *      worker-loop runs, long-horizon llama.cpp dispatch, etc.). Default
 *      `sbt test` skips them so a normal local run stays under a few
 *      minutes; `test-all.sh` opts in.
 *   3. A per-suite credential probe. When live is on and the suite's
 *      API key is present, we fire the supplied probe request; a 401,
 *      402, or 403 response cancels the suite cleanly so an
 *      unfunded / revoked / quota-drained key doesn't cascade into
 *      per-test failures.
 */
object LiveProbe {
  private val skipCodes: Set[Int] = Set(401, 402, 403)

  def liveEnabled: Boolean = sys.env.get("SIGIL_LIVE").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  def slowEnabled: Boolean = sys.env.get("SIGIL_SLOW").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  /** Skip cleanly when SIGIL_LIVE isn't set. Call at the top of each
    * provider-specific `runGated`. */
  def requireLiveEnabled(suite: Suite): Option[Status] =
    if (liveEnabled) None
    else {
      println(s"[skipped] ${suite.suiteName} — live tests disabled (set SIGIL_LIVE=1 to enable)")
      Some(SucceededStatus)
    }

  /** Skip cleanly when SIGIL_SLOW isn't set. Wrap a suite's `run`
    * with this when the spec routinely takes more than a few minutes
    * (live LLM workers iterating to settle, multi-iteration loops,
    * etc.) so day-to-day `sbt test` stays snappy. */
  def requireSlowEnabled(suite: Suite): Option[Status] =
    if (slowEnabled) None
    else {
      println(s"[skipped] ${suite.suiteName} — slow tests disabled (set SIGIL_SLOW=1 to enable)")
      Some(SucceededStatus)
    }

  def runGatedProbe(suite: Suite, reason: String => String, probe: => HttpRequest)(runBody: => Status): Status = {
    val codeOpt: Option[Int] = scala.util.Try {
      HttpClient.modify(_ => probe).noFailOnHttpStatus.send().sync().status.code
    }.toOption
    codeOpt match {
      case Some(c) if skipCodes.contains(c) =>
        println(s"[skipped] ${suite.suiteName} — ${reason(s"HTTP $c")}")
        SucceededStatus
      case _ => runBody
    }
  }
}
