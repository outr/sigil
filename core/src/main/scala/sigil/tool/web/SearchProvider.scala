package sigil.tool.web

import fabric.*
import fabric.rw.*
import rapid.Task
import spice.http.client.HttpClient
import spice.http.{Header, HeaderKey}
import spice.net.URL

import scala.concurrent.duration.*

/**
 * Pluggable web-search backend. Wire a concrete provider into
 * [[WebSearchTool]] at construction; the framework ships factory
 * methods for the common providers below.
 *
 * All factories return a thin SAM that drives a single REST call
 * via spice [[HttpClient]] and folds the response into
 * `List[SearchResult]`. Auth is per-provider (bearer / x-api-key
 * header / query param). Apps that want a non-listed backend can
 * use [[custom]].
 */
trait SearchProvider {
  def search(query: String, maxResults: Int = 10): Task[List[SearchResult]]
}

/**
 * Tavily's `search_depth` knob. `Basic` is ~1s and broad; `Advanced`
 * is ~3s and more relevant; `Fast` is the lower-latency shape Tavily
 * exposes for high-volume usage.
 */
sealed trait TavilySearchDepth(val value: String)
object TavilySearchDepth {
  case object Fast extends TavilySearchDepth("fast")
  case object Basic extends TavilySearchDepth("basic")
  case object Advanced extends TavilySearchDepth("advanced")
}

object SearchProvider {

  /**
   * Brave Search. Free-tier API key from <https://api.search.brave.com>.
   * Auth via `X-Subscription-Token`.
   */
  def brave(apiKey: String,
            baseUrl: URL = URL.parse("https://api.search.brave.com"),
            timeout: FiniteDuration = 30.seconds): SearchProvider =
    (query: String, maxResults: Int) =>
      HttpClient
        .url(baseUrl.withPath("/res/v1/web/search"))
        .params("q" -> query, "count" -> maxResults.toString)
        .header(Header(HeaderKey("X-Subscription-Token"), apiKey))
        .timeout(timeout)
        .call[Json]
        .map { json =>
          json("web")("results").asVector.toList.map { item =>
            SearchResult(
              title = item("title").asString,
              url = item("url").asString,
              snippet = item("description").asString
            )
          }
        }

  /**
   * Google Custom Search. Requires both an API key and a custom
   * search engine id (`cx`). Set up at
   * <https://programmablesearchengine.google.com>.
   */
  def google(apiKey: String,
             cx: String,
             baseUrl: URL = URL.parse("https://www.googleapis.com"),
             timeout: FiniteDuration = 30.seconds): SearchProvider =
    (query: String, maxResults: Int) =>
      HttpClient
        .url(baseUrl.withPath("/customsearch/v1"))
        .params(
          "key" -> apiKey,
          "cx" -> cx,
          "q" -> query,
          "num" -> maxResults.toString
        )
        .timeout(timeout)
        .call[Json]
        .map { json =>
          json("items").asVector.toList.map { item =>
            SearchResult(
              title = item("title").asString,
              url = item("link").asString,
              snippet = item("snippet").asString
            )
          }
        }

  /**
   * SerpAPI — Google scraper proxy. Auth via `api_key` query param.
   * <https://serpapi.com>
   */
  def serpApi(apiKey: String,
              baseUrl: URL = URL.parse("https://serpapi.com"),
              timeout: FiniteDuration = 30.seconds): SearchProvider =
    (query: String, maxResults: Int) =>
      HttpClient
        .url(baseUrl.withPath("/search.json"))
        .params(
          "api_key" -> apiKey,
          "q" -> query,
          "num" -> maxResults.toString
        )
        .timeout(timeout)
        .call[Json]
        .map { json =>
          json("organic_results").asVector.toList.map { item =>
            SearchResult(
              title = item("title").asString,
              url = item("link").asString,
              snippet = item("snippet").asString
            )
          }
        }

  /**
   * Serper.dev — Google search over POST. 2,500 free queries on
   * signup, no credit card. Auth via `X-API-KEY` header.
   * <https://serper.dev>
   */
  def serper(apiKey: String,
             baseUrl: URL = URL.parse("https://google.serper.dev"),
             timeout: FiniteDuration = 30.seconds): SearchProvider =
    (query: String, maxResults: Int) =>
      HttpClient
        .url(baseUrl.withPath("/search"))
        .header(Header(HeaderKey("X-API-KEY"), apiKey))
        .timeout(timeout)
        .json(obj("q" -> str(query), "num" -> num(maxResults)))
        .call[Json]
        .map { json =>
          json("organic").asVector.toList.map { item =>
            SearchResult(
              title = item("title").asString,
              url = item("link").asString,
              snippet = item("snippet").asString
            )
          }
        }

  /**
   * SearXNG — federated meta-search. Free, no API key. Point at
   * any public instance or self-hosted endpoint.
   */
  def searxng(baseUrl: URL,
              timeout: FiniteDuration = 30.seconds): SearchProvider =
    (query: String, maxResults: Int) =>
      HttpClient
        .url(baseUrl.withPath("/search"))
        .params(
          "q" -> query,
          "format" -> "json",
          "pageno" -> "1"
        )
        .timeout(timeout)
        .call[Json]
        .map { json =>
          json("results").asVector.toList.take(maxResults).map { item =>
            SearchResult(
              title = item("title").asString,
              url = item("url").asString,
              snippet = item.get("content").map(_.asString).getOrElse("")
            )
          }
        }

  /**
   * Tavily — purpose-built for LLM grounding. Returns scored hits
   * plus an optional AI-synthesized `answer` (folded as a synthetic
   * top result with score 1.0 when present). Free tier available;
   * auth via `Authorization: Bearer <apiKey>`.
   * <https://tavily.com>
   */
  def tavily(apiKey: String,
             searchDepth: TavilySearchDepth = TavilySearchDepth.Basic,
             baseUrl: URL = URL.parse("https://api.tavily.com"),
             timeout: FiniteDuration = 30.seconds): SearchProvider =
    (query: String, maxResults: Int) => {
      val body = obj(
        "query" -> str(query),
        "max_results" -> num(maxResults),
        "search_depth" -> str(searchDepth.value)
      )
      HttpClient
        .url(baseUrl.withPath("/search"))
        .header(Header(HeaderKey("Authorization"), s"Bearer $apiKey"))
        .timeout(timeout)
        .json(body)
        .call[Json]
        .map { json =>
          val results = json("results").asVector.toList.map { item =>
            SearchResult(
              title = item("title").asString,
              url = item("url").asString,
              snippet = item("content").asString,
              score = item.get("score").map(_.asDouble),
              rawContent = item.get("raw_content").map(_.asString)
            )
          }
          json.get("answer").map(_.asString).filter(_.nonEmpty) match {
            case Some(ans) =>
              SearchResult(
                title = "Tavily Answer",
                url = "",
                snippet = ans,
                score = Some(1.0)
              ) :: results
            case None => results
          }
        }
    }

  /**
   * DuckDuckGo Instant Answer API — free, no API key. Best-effort:
   * the API returns abstract / heading / topic-list shapes rather
   * than a typical "10 blue links" result set, so coverage of
   * arbitrary queries is uneven. Useful as a free fallback.
   */
  def duckDuckGo(timeout: FiniteDuration = 30.seconds): SearchProvider =
    (query: String, maxResults: Int) =>
      HttpClient
        .url(URL.parse("https://api.duckduckgo.com/"))
        .params("q" -> query, "format" -> "json", "no_html" -> "1")
        .timeout(timeout)
        .call[Json]
        .map { json =>
          val results = scala.collection.mutable.ListBuffer.empty[SearchResult]

          val abstractText = json.get("Abstract").map(_.asString).getOrElse("")
          val abstractUrl = json.get("AbstractURL").map(_.asString).getOrElse("")
          val heading = json.get("Heading").map(_.asString).getOrElse("")
          if (abstractText.nonEmpty && abstractUrl.nonEmpty)
            results += SearchResult(
              title = heading,
              url = abstractUrl,
              snippet = abstractText
            )

          for (item <- json.get("Results").toList.flatMap(_.asVector.toList)) {
            val u = item.get("FirstURL").map(_.asString).getOrElse("")
            val t = item.get("Text").map(_.asString).getOrElse("")
            if (u.nonEmpty && t.nonEmpty)
              results += SearchResult(title = t.take(100), url = u, snippet = t)
          }

          def extractTopics(topics: List[Json]): Unit =
            for (item <- topics)
              item.get("FirstURL") match {
                case Some(urlJson) =>
                  val u = urlJson.asString
                  val t = item.get("Text").map(_.asString).getOrElse("")
                  if (u.nonEmpty && t.nonEmpty)
                    results += SearchResult(title = t.take(100), url = u, snippet = t)
                case None =>
                  item.get("Topics").foreach(sub => extractTopics(sub.asVector.toList))
              }
          json.get("RelatedTopics").foreach(rt => extractTopics(rt.asVector.toList))

          results.toList.take(maxResults)
        }

  /**
   * Custom search provider built from a user-supplied function. Use
   * for backends the framework doesn't ship a factory for, or for
   * test fixtures (in-memory result sets keyed by query).
   */
  def custom(fn: (String, Int) => Task[List[SearchResult]]): SearchProvider =
    (query: String, maxResults: Int) => fn(query, maxResults)
}
