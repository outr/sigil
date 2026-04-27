package sigil.tool.web

import fabric.rw.*

/** Single search result. `snippet` is a short excerpt the search
  * provider returns; `score` is a relevance signal when the
  * provider supplies one (1.0 by default). */
case class SearchResult(title: String,
                        url: String,
                        snippet: String,
                        score: Double = 1.0) derives RW
