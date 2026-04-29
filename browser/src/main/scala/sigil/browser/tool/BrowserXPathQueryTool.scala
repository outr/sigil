package sigil.browser.tool

import fabric.{Json, arr, num, obj, str}
import lightdb.id.Id
import org.jsoup.Jsoup
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.storage.StoredFile
import sigil.tool.{ToolExample, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

/**
 * Run an XPath query against a previously saved HTML document
 * (typically the result of `browser_save_html`) and return a
 * structured projection of the matched nodes.
 *
 * The agent gets `tag` / `text` / `attributes` for every match by
 * default; `outerHtml` is opt-in via `includeOuterHtml=true` to
 * avoid blowing the context window. `maxResults` caps the returned
 * matches; `totalCount` reports the unbounded match count so the
 * agent knows whether to refine the query.
 */
final class BrowserXPathQueryTool extends TypedTool[BrowserXPathQueryInput](
  name = ToolName("browser_xpath_query"),
  description =
    """Run an XPath query against an HTML file saved earlier (use the `htmlFileId` from `browser_save_html`).
      |Returns matched nodes as `{tag, text, attributes}`. Set `includeOuterHtml=true` only when you need raw markup —
      |it can be large. Use the overview's `headings`, `landmarks`, and `linkClusters` xpaths as starting points.""".stripMargin,
  examples = List(
    ToolExample(
      "Pull all article links from a list",
      BrowserXPathQueryInput(htmlFileId = "abc123", xpath = "//main//a[@href]", maxResults = 50)
    ),
    ToolExample(
      "Get the page's main heading element with markup",
      BrowserXPathQueryInput(htmlFileId = "abc123", xpath = "//h1[1]", includeOuterHtml = true)
    )
  ),
  keywords = Set("browser", "xpath", "query", "extract", "html", "structure")
) {

  override protected def executeTyped(input: BrowserXPathQueryInput, ctx: TurnContext): Stream[Event] =
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
          val all  = doc.selectXpath(input.xpath).iterator().asScala.toList
          val totalCount = all.size
          val limited    = all.take(input.maxResults)

          val matches: List[Json] = limited.map { el =>
            val attrs = el.attributes().iterator().asScala.toList.map { a =>
              a.getKey -> str(a.getValue)
            }
            val base = List(
              "xpath"      -> str(BrowserHtmlOverview.xpathOf(el)),
              "tag"        -> str(el.tagName()),
              "text"       -> str(BrowserHtmlOverview.squish(el.text()).take(500)),
              "attributes" -> obj(attrs*)
            )
            val full =
              if (input.includeOuterHtml) base :+ ("outerHtml" -> str(el.outerHtml().take(4000)))
              else base
            obj(full*)
          }

          Stream.emit[Event](BrowserToolBase.toolResult(
            obj(
              "htmlFileId" -> str(input.htmlFileId),
              "xpath"      -> str(input.xpath),
              "matches"    -> arr(matches*),
              "totalCount" -> num(totalCount),
              "returned"   -> num(limited.size)
            ),
            ctx
          ))
      }
    )
}
