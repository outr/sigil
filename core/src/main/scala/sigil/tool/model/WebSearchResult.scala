package sigil.tool.model

import fabric.rw.*

/**
 * One hit in a [[WebSearchOutput]]. `title` / `url` / `snippet` are
 * always present; `score` is the backend's relevance signal when it
 * supplies one; `rawContent` is the full page text when the backend
 * returns it (Tavily's `raw_content`, etc.) — useful as direct input
 * to summarization without a follow-up fetch.
 */
case class WebSearchResult(title: String,
                           url: String,
                           snippet: String,
                           score: Option[Double] = None,
                           rawContent: Option[String] = None)
  derives RW
