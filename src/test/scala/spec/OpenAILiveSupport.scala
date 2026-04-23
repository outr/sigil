package spec

import org.scalatest.{Args, Status, SucceededStatus, Suite}

/**
 * Shared gating for specs that require a live OpenAI API key. Each
 * spec overrides `run` to defer to [[runGated]] — when
 * `OPENAI_API_KEY` is missing, the suite prints a single skip
 * message and returns [[SucceededStatus]] instead of executing the
 * tests. No per-test cancellations, no noise in the reporter.
 */
object OpenAILiveSupport {
  def apiKey: Option[String] = sys.env.get("OPENAI_API_KEY").filter(_.nonEmpty)

  def runGated(suite: Suite, testName: Option[String], args: Args)(runBody: => Status): Status =
    apiKey match {
      case Some(_) => runBody
      case None =>
        println(s"[skipped] ${suite.suiteName} — OPENAI_API_KEY not set")
        SucceededStatus
    }
}
