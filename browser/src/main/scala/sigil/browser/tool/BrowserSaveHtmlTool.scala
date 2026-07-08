package sigil.browser.tool

import fabric.rw.*
import fabric.{num, obj, str}
import org.jsoup.Jsoup
import rapid.Task
import robobrowser.select.Selector
import sigil.browser.BrowserStateDelta
import sigil.browser.WebBrowserMode
import sigil.information.StoredInformation
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}
import sigil.GlobalSpace
import sigil.tool.ToolContext

/**
 * Capture the current page's outer HTML, normalize via jSoup so the
 * resulting bytes are well-formed, persist it BOTH as a raw
 * `StoredFile` (for the fragment tools) AND as a lookup-able
 * `Information`, and return a structural overview the
 * agent uses to plan `browser_xpath_query` / `browser_text_search`
 * calls.
 *
 * The overview JSON summarizes headings, landmarks, link clusters, and
 * totals, and carries the `informationId` plus a `readWhole` hint: the
 * agent can `lookup(capabilityType="Information", name=<id>)` to read
 * the entire document at once — the same way it reads an uploaded HTML
 * file — rather than being forced to snippet-hunt. Modest pages steer
 * toward reading the whole doc; large pages toward the fragment tools.
 */
final class BrowserSaveHtmlTool extends Tool {
  type Input  = BrowserSaveHtmlInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[BrowserSaveHtmlInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("browser_save_html")
  val description =
    """Persist the current page's HTML and return a compact structural overview (headings, landmarks, link clusters,
      |totals) plus an `htmlFileId` and an `informationId`. To read the WHOLE page (e.g. to understand or faithfully
      |reproduce it), call lookup(capabilityType="Information", name=<informationId>) — the same way you read an uploaded
      |HTML file. For surgical extraction from a large page, pass the `htmlFileId` to `browser_xpath_query` or
      |`browser_text_search`. Call once per page; repeat after navigation.""".stripMargin
  override val examples = List(
    ToolExample("Save the current page", BrowserSaveHtmlInput())
  )
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("browser", "html", "save", "snapshot", "overview", "structure")

  override def executeResult(input: BrowserSaveHtmlInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    for {
      controller <- BrowserToolBase.resolveController(ctx)
      capture    <- controller.run { browser =>
                      for {
                        html <- browser(Selector("html")).outerHTML.map(_.headOption.getOrElse(""))
                      } yield (html, browser.url())
                    }
      (rawHtml, currentUrl) = capture
      doc        = Jsoup.parse(rawHtml)
      normalized = doc.outerHtml()
      bytes      = normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      stored     <- ctx.sigil.storeBytes(GlobalSpace, bytes, "text/html",
                      metadata = Map(
                        "kind" -> "browser-html",
                        "conversationId" -> ctx.conversation.id.value,
                        "url" -> currentUrl
                      ))
      // also register the page as a lookup-able Information
      // (the subsystem an uploaded HTML file lands in) so the agent can
      // read the whole document via `lookup`, exactly as it reads an
      // uploaded file, instead of snippet-hunting through
      // browser_xpath_query / browser_text_search. The StoredFile stays
      // for those fragment tools.
      info       = StoredInformation(content = normalized, space = GlobalSpace)
      _          <- ctx.sigil.putInformation(info)
      _          <- ctx.sigil.publish(BrowserStateDelta(
                      target         = controller.stateId,
                      conversationId = ctx.conversation.id,
                      htmlFileId     = Some(stored._id)
                    ))
    } yield {
      val informationId = info.id.value
      val readWhole =
        if (bytes.length <= BrowserSaveHtmlTool.readWholeThresholdBytes)
          s"""Full page stored as Information[$informationId]. Call lookup(capabilityType="Information", """ +
            s"""name="$informationId") to read the entire document at once — preferred for understanding or """ +
            "faithfully reproducing the page."
        else
          s"Large page (${bytes.length / 1024} KB). Prefer browser_xpath_query / browser_text_search (with this " +
            s"""htmlFileId) for surgical extraction; the whole document is also available via """ +
            s"""lookup(capabilityType="Information", name="$informationId"), which loads the full page into context."""
      val payload = BrowserHtmlOverview.overview(doc, stored._id.value, currentUrl).merge(obj(
        "informationId" -> str(informationId),
        "sizeBytes"     -> num(bytes.length),
        "readWhole"     -> str(readWhole)
      ))
      ToolResult.Success(BrowserToolBase.toolResult(payload))
    }
}

object BrowserSaveHtmlTool {
  /** Pages at or below this rendered byte size are
    * recommended for whole-document `lookup`; larger pages steer the
    * agent toward the fragment tools (with `lookup` still available). */
  val readWholeThresholdBytes: Int = 200_000
}
