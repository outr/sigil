package sigil.tool.web

import rapid.Task

/**
 * Pluggable web-search backend. The framework ships no concrete
 * implementation — apps inject Tavily, Brave, Google Custom Search,
 * SerpAPI, etc. via a class implementing this trait, and pass the
 * instance to [[WebSearchTool]] at construction.
 */
trait SearchProvider {
  def search(query: String, maxResults: Int = 10): Task[List[SearchResult]]
}
