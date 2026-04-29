package sigil.browser.tool

import fabric.{Json, arr, num, obj, str}
import org.jsoup.nodes.{Document, Element, Node}

import scala.jdk.CollectionConverters.*

/**
 * Pure jSoup helpers shared between [[BrowserSaveHtmlTool]] and any
 * follow-on step that wants to compute the structural overview the
 * agent uses to plan `browser_xpath_query` / `browser_text_search`
 * calls without ever loading the raw HTML into the prompt.
 *
 * The overview is deliberately compact (target ≤2KB JSON):
 *   - `headings` — every `h1`-`h6`, with depth + xpath
 *   - `landmarks` — `main`/`nav`/`article`/`aside`/`header`/`footer`
 *     containers with child counts, so the agent picks the right
 *     parent to query
 *   - `linkClusters` — parent xpaths that hold ≥2 anchors, so the
 *     agent sees "12 links inside `/html/body/main/ul`" rather than
 *     a flat list
 *   - `totals` — link / image / form counts for sanity-checking
 */
private[browser] object BrowserHtmlOverview {

  /** Build the overview JSON for a parsed jSoup [[Document]]. */
  def overview(doc: Document, htmlFileId: String, currentUrl: String): Json = {
    val title = Option(doc.title()).getOrElse("")

    val headings: List[Json] = (1 to 6).flatMap { lvl =>
      doc.select(s"h$lvl").iterator().asScala.toList.map { el =>
        obj(
          "level" -> num(lvl),
          "text"  -> str(squish(el.text()).take(140)),
          "xpath" -> str(xpathOf(el))
        )
      }
    }.toList.take(60)

    val landmarkTags = List("main", "nav", "article", "aside", "header", "footer", "section")
    val landmarks: List[Json] = landmarkTags.flatMap { tag =>
      doc.select(tag).iterator().asScala.toList.map { el =>
        obj(
          "tag"        -> str(tag),
          "xpath"      -> str(xpathOf(el)),
          "childCount" -> num(el.children().size())
        )
      }
    }.take(40)

    val linkClusters: List[Json] = {
      val anchors = doc.select("a[href]").iterator().asScala.toList
      val byParent = anchors
        .flatMap(a => Option(a.parent()).map(p => (xpathOf(p), p)))
        .groupBy(_._1)
        .view
        .mapValues(_.size)
        .toList
        .filter(_._2 >= 2)
        .sortBy(-_._2)
        .take(20)
      byParent.map { case (xp, count) =>
        obj("xpath" -> str(xp), "count" -> num(count))
      }
    }

    val totals = obj(
      "links"  -> num(doc.select("a[href]").size()),
      "images" -> num(doc.select("img[src]").size()),
      "forms"  -> num(doc.select("form").size())
    )

    obj(
      "htmlFileId"   -> str(htmlFileId),
      "url"          -> str(currentUrl),
      "title"        -> str(title.take(200)),
      "headings"     -> arr(headings*),
      "landmarks"    -> arr(landmarks*),
      "linkClusters" -> arr(linkClusters*),
      "totals"       -> totals
    )
  }

  /** Build a stable XPath for an [[Element]] by walking parents and
    * emitting `tag[index]` segments. Matches jSoup's `selectXpath`
    * dialect so round-tripping a returned xpath works. Indexes are
    * 1-based, per XPath convention; same-tag siblings get the
    * positional index. */
  def xpathOf(el: Element): String = {
    val segments = scala.collection.mutable.ListBuffer.empty[String]
    var current: Node = el
    while (current != null && current.isInstanceOf[Element]) {
      val cur = current.asInstanceOf[Element]
      val parent = cur.parent()
      if (parent == null) {
        segments.prepend(cur.tagName())
        current = null
      } else {
        val tag = cur.tagName()
        val sameTagSiblings = parent.children().iterator().asScala.toList.filter(_.tagName() == tag)
        if (sameTagSiblings.size <= 1) {
          segments.prepend(tag)
        } else {
          val idx = sameTagSiblings.indexOf(cur) + 1
          segments.prepend(s"$tag[$idx]")
        }
        current = parent
      }
    }
    "/" + segments.mkString("/")
  }

  /** Collapse runs of whitespace to single spaces; trim. Used so the
    * overview's heading text doesn't dump newline-padded markup at
    * the agent. */
  def squish(text: String): String =
    text.replaceAll("\\s+", " ").trim
}
