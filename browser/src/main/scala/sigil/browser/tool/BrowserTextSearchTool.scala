package sigil.browser.tool

import fabric.{Json, arr, num, obj, str}
import lightdb.id.Id
import org.jsoup.Jsoup
import org.jsoup.nodes.{Element, TextNode}
import org.jsoup.select.{NodeTraversor, NodeVisitor}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.storage.StoredFile
import sigil.tool.{ToolExample, ToolName, TypedTool}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Substring-search the visible body text of a previously saved HTML
 * document. For each match emit `{position, contextBefore, matchText,
 * contextAfter, containingXPath}` — the containing element xpath is
 * the agent's pivot back to `browser_xpath_query` for structural
 * extraction.
 *
 * Default search is case-insensitive; `caseSensitive=true` flips it.
 * `maxResults` caps the returned matches; `totalCount` reports the
 * unbounded count.
 */
final class BrowserTextSearchTool extends TypedTool[BrowserTextSearchInput](
  name = ToolName("browser_text_search"),
  description =
    """Substring-search the visible text of an HTML file saved earlier (use the `htmlFileId` from `browser_save_html`).
      |Each match returns surrounding context plus the containing element's xpath, so you can pivot to
      |`browser_xpath_query` to extract structure around the hit. Default case-insensitive.""".stripMargin,
  examples = List(
    ToolExample(
      "Find every occurrence of a person's name",
      BrowserTextSearchInput(htmlFileId = "abc123", query = "Alice")
    ),
    ToolExample(
      "Find a specific phrase, more context per hit",
      BrowserTextSearchInput(htmlFileId = "abc123", query = "Section 3.2", contextChars = 200)
    )
  ),
  keywords = Set("browser", "text", "search", "find", "substring", "query")
) {

  override protected def executeTyped(input: BrowserTextSearchInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      ctx.sigil.fetchStoredFile(Id[StoredFile](input.htmlFileId), ctx.chain).map {
        case None =>
          Stream.emit[Event](BrowserToolBase.toolResult(
            obj(
              "error"      -> str(s"htmlFileId '${input.htmlFileId}' not found or not authorized"),
              "matches"    -> arr(),
              "totalCount" -> num(0),
              "returned"   -> num(0)
            ),
            ctx
          ))
        case Some((_, bytes)) =>
          val html = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
          val doc  = Jsoup.parse(html)

          // Walk the body's text nodes, building (position, textNode) pairs against
          // a single concatenated body text. We squish whitespace per node so the
          // search positions correspond roughly to what jSoup's `body().text()`
          // returns — close enough for human-meaningful "find X on the page".
          val body = Option(doc.body()).getOrElse(doc)
          val flat = new StringBuilder
          val nodeOffsets = mutable.ArrayBuffer.empty[(Int, TextNode)]

          val visitor = new NodeVisitor {
            override def head(node: org.jsoup.nodes.Node, depth: Int): Unit = node match {
              case t: TextNode if !t.isBlank =>
                val piece = BrowserHtmlOverview.squish(t.text())
                if (piece.nonEmpty) {
                  if (flat.nonEmpty && !flat.charAt(flat.length - 1).isWhitespace) flat.append(' ')
                  nodeOffsets += ((flat.length, t))
                  flat.append(piece)
                }
              case _ => ()
            }
            override def tail(node: org.jsoup.nodes.Node, depth: Int): Unit = ()
          }
          NodeTraversor.traverse(visitor, body)

          val haystack    = flat.toString
          val needle      = input.query
          val needleLower = if (input.caseSensitive) needle else needle.toLowerCase
          val haystackLU  = if (input.caseSensitive) haystack else haystack.toLowerCase

          // Collect all match positions.
          val positions = mutable.ListBuffer.empty[Int]
          if (needleLower.nonEmpty) {
            var idx = haystackLU.indexOf(needleLower)
            while (idx >= 0) {
              positions += idx
              idx = haystackLU.indexOf(needleLower, idx + needleLower.length)
            }
          }

          val totalCount = positions.size
          val limited    = positions.take(input.maxResults).toList

          val matches: List[Json] = limited.map { pos =>
            val matchText    = haystack.substring(pos, math.min(pos + needle.length, haystack.length))
            val ctxStart     = math.max(0, pos - input.contextChars)
            val ctxEndBefore = pos
            val ctxStartAfter = math.min(haystack.length, pos + matchText.length)
            val ctxEndAfter   = math.min(haystack.length, ctxStartAfter + input.contextChars)
            val before        = haystack.substring(ctxStart, ctxEndBefore)
            val after         = haystack.substring(ctxStartAfter, ctxEndAfter)

            // Find the TextNode whose offset range contains pos — last
            // entry whose start offset ≤ pos. nodeOffsets is in source
            // order so this is monotonic.
            val containing: Option[TextNode] = {
              var i = 0
              var found: Option[TextNode] = None
              while (i < nodeOffsets.size && nodeOffsets(i)._1 <= pos) {
                found = Some(nodeOffsets(i)._2)
                i += 1
              }
              found
            }
            val containingXPath = containing.flatMap(t => Option(t.parent())).collect {
              case el: Element => BrowserHtmlOverview.xpathOf(el)
            }.getOrElse("")

            obj(
              "position"        -> num(pos),
              "contextBefore"   -> str(before),
              "matchText"       -> str(matchText),
              "contextAfter"    -> str(after),
              "containingXPath" -> str(containingXPath)
            )
          }

          Stream.emit[Event](BrowserToolBase.toolResult(
            obj(
              "htmlFileId" -> str(input.htmlFileId),
              "query"      -> str(needle),
              "matches"    -> arr(matches*),
              "totalCount" -> num(totalCount),
              "returned"   -> num(limited.size)
            ),
            ctx
          ))
      }
    )
}
