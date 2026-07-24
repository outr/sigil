package sigil.tool.web

import fabric.rw.*

/**
 * Single search result from a [[SearchProvider]].
 *
 * `snippet` is the short excerpt the backend returns; `score` is a
 * relevance signal (some backends emit it, others don't, hence
 * `Option`). `rawContent` is the full page-text body when the
 * backend supplies it (Tavily's `raw_content`, Serper's full-page
 * fetch); useful as input to downstream summarization without
 * a follow-up fetch.
 */
case class SearchResult(title: String,
                        url: String,
                        snippet: String,
                        score: Option[Double] = None,
                        rawContent: Option[String] = None)
  derives RW
