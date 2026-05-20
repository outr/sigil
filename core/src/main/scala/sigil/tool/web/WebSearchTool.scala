package sigil.tool.web

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{WebSearchInput, WebSearchOutput, WebSearchResult}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * Search the web via the configured [[SearchProvider]] (Tavily,
 * Brave, etc. — provider type is the app's choice). Emits a typed
 * [[WebSearchOutput]] carrying the ranked hit list.
 */
final class WebSearchTool(provider: SearchProvider, defaultMaxResults: Int = 10)
  extends TypedOutputTool[WebSearchInput, WebSearchOutput](
    name = ToolName("web_search"),
    description =
      """Search the web for `query`. Returns up to `maxResults` results (default 10) — each carrying title,
        |URL, snippet, and (when the backend supplies one) a relevance score.""".stripMargin,
    examples = List(
      ToolExample("General lookup", WebSearchInput(query = "Scala 3 enums tutorial")),
      ToolExample("Top 5 only", WebSearchInput(query = "weather Tokyo today", maxResults = Some(5)))
    ),
    keywords = Set("web", "search", "google", "find", "lookup", "query", "internet")
  ) with sigil.tool.NetworkReadOnlyTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: WebSearchInput, ctx: TurnContext): Task[WebSearchOutput] =
    provider.search(input.query, input.maxResults.getOrElse(defaultMaxResults)).map { results =>
      val items = results.toList.map { r =>
        WebSearchResult(
          title      = r.title,
          url        = r.url,
          snippet    = r.snippet,
          score      = r.score,
          rawContent = r.rawContent
        )
      }
      WebSearchOutput(results = items, count = items.size)
    }
}
