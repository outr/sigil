package spec

import fabric.*
import fabric.io.JsonFormatter
import org.scalatest.{Args, Status, SucceededStatus, Suite}
import spice.http.{HttpMethod, HttpRequest}
import spice.http.content.StringContent
import spice.net.*

object DeepSeekLiveSupport {
  def apiKey: Option[String] = sys.env.get("DEEPSEEK_API_KEY").filter(_.nonEmpty)

  /** DeepSeek's `/v1/models` returns 200 even for unfunded keys, so
    * the only reliable "is this account usable" probe is a minimal
    * chat-completions request — unfunded keys come back 402. */
  private def probe(key: String): HttpRequest = {
    val body = JsonFormatter.Compact(obj(
      "model" -> str("deepseek-chat"),
      "messages" -> arr(obj("role" -> str("user"), "content" -> str("hi"))),
      "max_tokens" -> num(1),
      "stream" -> bool(false)
    ))
    HttpRequest(
      method = HttpMethod.Post,
      url = url"https://api.deepseek.com/v1/chat/completions",
      content = Some(StringContent(body, ContentType.`application/json`))
    ).withHeader("Authorization", s"Bearer $key")
  }

  def runGated(suite: Suite, testName: Option[String], args: Args)(runBody: => Status): Status =
    LiveProbe.requireLiveEnabled(suite).getOrElse {
      apiKey match {
        case None =>
          println(s"[skipped] ${suite.suiteName} — DEEPSEEK_API_KEY not set")
          SucceededStatus
        case Some(key) =>
          LiveProbe.runGatedProbe(suite, c => s"DEEPSEEK_API_KEY rejected by DeepSeek ($c)", probe(key))(runBody)
      }
    }
}
