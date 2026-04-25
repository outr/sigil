package spec

import org.scalatest.{Args, Status, SucceededStatus, Suite}

/**
 * Suite-level gating for specs that require a live Qdrant endpoint.
 * Reads `SIGIL_QDRANT_URL` (env) / `sigil.qdrant.url` (Profig); when
 * absent, the whole suite prints a single skip line and returns
 * [[SucceededStatus]] instead of running tests.
 *
 * Same pattern as [[OpenAILiveSupport]].
 */
object QdrantLiveSupport {
  def baseUrl: Option[spice.net.URL] = {
    import fabric.rw.*
    profig.Profig.initConfiguration()
    profig.Profig("sigil.qdrant.url").opt[String].filter(_.nonEmpty)
      .flatMap(s => spice.net.URL.get(s).toOption)
  }

  def runGated(suite: Suite, testName: Option[String], args: Args)(runBody: => Status): Status =
    baseUrl match {
      case Some(_) => runBody
      case None =>
        println(s"[skipped] ${suite.suiteName} — SIGIL_QDRANT_URL not set")
        SucceededStatus
    }
}
