package sigil.browser.tool

import fabric.rw.*
import org.jsoup.Jsoup
import rapid.Task
import robobrowser.select.Selector
import sigil.browser.BrowserStateDelta
import sigil.browser.WebBrowserMode
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}
import sigil.{GlobalSpace, TurnContext}

/**
 * Capture the current page's outer HTML, normalize via jSoup so the
 * resulting bytes are well-formed, persist to `Sigil.storeBytes`
 * under [[GlobalSpace]], and return a structural overview the agent
 * uses to plan `browser_xpath_query` / `browser_text_search` calls.
 *
 * The agent never sees the raw HTML in its context — only the
 * `htmlFileId` (for follow-on tools) plus the small overview JSON
 * that summarizes headings, landmarks, link clusters, and totals.
 */
final class BrowserSaveHtmlTool extends Tool {
  type Input  = BrowserSaveHtmlInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[BrowserSaveHtmlInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("browser_save_html")
  val description =
    """Persist the current page's HTML and return a compact structural overview (headings, landmarks, link clusters,
      |totals) plus an `htmlFileId`. Pass that id to `browser_xpath_query` or `browser_text_search` to extract specific
      |fragments without loading the whole page into your prompt. Call once per page; repeat after navigation.""".stripMargin
  override val examples = List(
    ToolExample("Save the current page", BrowserSaveHtmlInput())
  )
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("browser", "html", "save", "snapshot", "overview", "structure")

  override def executeResult(input: BrowserSaveHtmlInput,
                             ctx: TurnContext): Task[ToolResult[TextToolOutput]] =
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
      _          <- ctx.sigil.publish(BrowserStateDelta(
                      target         = controller.stateId,
                      conversationId = ctx.conversation.id,
                      htmlFileId     = Some(stored._id)
                    ))
    } yield {
      val payload = BrowserHtmlOverview.overview(doc, stored._id.value, currentUrl)
      ToolResult.Success(BrowserToolBase.toolResult(payload))
    }
}
