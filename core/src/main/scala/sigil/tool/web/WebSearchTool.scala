package sigil.tool.web

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{WebSearchInput, WebSearchOutput, WebSearchResult}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolSpec}

/**
 * Search the web via the configured [[SearchProvider]] (Tavily,
 * Brave, etc. — provider type is the app's choice). Emits a typed
 * [[WebSearchOutput]] carrying the ranked hit list.
 */
final class WebSearchTool(provider: SearchProvider, defaultMaxResults: Int = 10) extends Tool {
  type Input  = WebSearchInput
  type Output = WebSearchOutput
  val io: ToolIO[WebSearchInput, WebSearchOutput] = ToolIO.derived[WebSearchInput, WebSearchOutput].withExamples(
    ToolExample("General lookup", WebSearchInput(query = "Scala 3 enums tutorial")),
    ToolExample("Top 5 only", WebSearchInput(query = "weather Tokyo today", maxResults = Some(5)))
  )
  override val name = ToolName("web_search")
  override val description =
    """Search the web for `query`. Returns up to `maxResults` results (default 10) — each carrying title,
      |URL, snippet, and (when the backend supplies one) a relevance score.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("web", "search", "google", "find", "lookup", "query", "internet"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: WebSearchInput, ctx: ToolContext): Task[WebSearchOutput] =
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
