package spec

import rapid.Task
import scribe.mdc.MDC
import spice.http.HttpExchange
import spice.http.content.Content
import spice.http.server.MutableHttpServer
import spice.http.server.config.HttpServerListener
import spice.http.server.dsl.{ConnectionFilter, FilterResponse}
import spice.net.ContentType

import scala.language.implicitConversions

/**
 * Per-suite Undertow server that hosts a synthetic news index page
 * for [[NewsArticleDetectionSpec]].
 *
 * The fixture HTML has 10 article-shaped links (date+slug paths) and
 * 5 nav/footer/asset links. The agent must read the page, classify
 * the links, and surface only the articles.
 */
final class NewsFixtureServer {

  val articleUrls: List[String] = List(
    "https://news.example/2026/04/29/scala-3-9-released-with-revamped-typeclass-derivation",
    "https://news.example/2026/04/28/anthropic-publishes-new-tool-use-benchmark-suite",
    "https://news.example/2026/04/27/openai-rolls-out-multimodal-fine-tuning-api-for-tier-4-users",
    "https://news.example/2026/04/26/jvm-25-stabilizes-virtual-threads-for-database-drivers",
    "https://news.example/2026/04/25/postgresql-19-debuts-async-replication-improvements",
    "https://news.example/2026/04/24/llama-4-cookbook-released-by-meta-research",
    "https://news.example/2026/04/23/cdp-changes-coming-to-chrome-130-affecting-headless-tools",
    "https://news.example/2026/04/22/rust-2026-edition-rfc-finalized",
    "https://news.example/2026/04/21/golang-1-25-improves-generics-inference-significantly",
    "https://news.example/2026/04/20/typescript-6-0-merges-with-deno-runtime"
  )

  val nonArticleUrls: List[String] = List(
    "https://news.example/about",
    "https://news.example/contact",
    "https://news.example/privacy",
    "https://news.example/login",
    "https://news.example/page/2"
  )

  private val html: String = {
    val articles = articleUrls.zipWithIndex.map { case (u, i) =>
      s"""<li><a href="$u">Headline ${i + 1}</a></li>"""
    }.mkString("\n      ")
    val nav = nonArticleUrls.map(u => s"""<li><a href="$u">${u.split('/').last}</a></li>""").mkString("\n      ")
    s"""<!doctype html>
       |<html lang="en">
       |<head><title>Example News</title></head>
       |<body>
       |  <header>
       |    <nav>
       |      <ul>
       |        $nav
       |      </ul>
       |    </nav>
       |  </header>
       |  <main>
       |    <h1>Latest articles</h1>
       |    <ul>
       |      $articles
       |    </ul>
       |  </main>
       |</body>
       |</html>""".stripMargin
  }

  private val server: MutableHttpServer = {
    val s = new MutableHttpServer
    s.config.clearListeners().addListeners(HttpServerListener(port = None))
    s.handler.handle { exchange =>
      val path = exchange.request.url.path.decoded
      if (path == "/" || path == "/index.html") {
        exchange.modify { response =>
          Task(response.withContent(Content.string(html, ContentType.`text/html`)))
        }
      } else {
        Task.pure(exchange)
      }
    }
    s
  }

  def start(): Task[Unit] = Task { server.start().sync(); () }

  def stop(): Task[Unit] = Task {
    try server.stop().sync() catch { case _: Throwable => () }
  }

  def port: Int = server.config.listeners().head.port.getOrElse(0)

  def indexUrl: String = s"http://localhost:$port/"
}
