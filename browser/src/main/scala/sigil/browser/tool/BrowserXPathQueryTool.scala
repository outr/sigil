package sigil.browser.tool

import fabric.rw.*
import fabric.{Json, arr, num, obj, str}
import lightdb.id.Id
import org.jsoup.Jsoup
import rapid.Task
import sigil.TurnContext
import sigil.browser.WebBrowserMode
import sigil.storage.StoredFile
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

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
final class BrowserXPathQueryTool extends Tool {
  type Input  = BrowserXPathQueryInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[BrowserXPathQueryInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("browser_xpath_query")
  val description =
    """Run an XPath query against an HTML file saved earlier (use the `htmlFileId` from `browser_save_html`).
      |Returns matched nodes as `{tag, text, attributes}`. Set `includeOuterHtml=true` only when you need raw markup —
      |it can be large. Use the overview's `headings`, `landmarks`, and `linkClusters` xpaths as starting points.""".stripMargin
  override val examples = List(
    ToolExample(
      "Pull all article links from a list",
      BrowserXPathQueryInput(htmlFileId = "abc123", xpath = "//main//a[@href]", maxResults = 50)
    ),
    ToolExample(
      "Get the page's main heading element with markup",
      BrowserXPathQueryInput(htmlFileId = "abc123", xpath = "//h1[1]", includeOuterHtml = true)
    )
  )
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("browser", "xpath", "query", "extract", "html", "structure")

  override def executeResult(input: BrowserXPathQueryInput,
                             ctx: TurnContext): Task[ToolResult[TextToolOutput]] =
    ctx.sigil.fetchStoredFile(Id[StoredFile](input.htmlFileId), ctx.chain).map {
        case None =>
          ToolResult.failure(s"htmlFileId '${input.htmlFileId}' not found or not authorized")
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

          ToolResult.Success(BrowserToolBase.toolResult(
            obj(
              "htmlFileId" -> str(input.htmlFileId),
              "xpath"      -> str(input.xpath),
              "matches"    -> arr(matches*),
              "totalCount" -> num(totalCount),
              "returned"   -> num(limited.size)
            )
          ))
    }
}
