package sigil.browser.tool

import org.jsoup.Jsoup
import rapid.{Stream, Task}
import robobrowser.select.Selector
import sigil.{GlobalSpace, TurnContext}
import sigil.browser.BrowserStateDelta
import sigil.event.Event
import sigil.tool.{ToolExample, ToolName, TypedTool}

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
final class BrowserSaveHtmlTool extends TypedTool[BrowserSaveHtmlInput](
  name = ToolName("browser_save_html"),
  description =
    """Persist the current page's HTML and return a compact structural overview (headings, landmarks, link clusters,
      |totals) plus an `htmlFileId`. Pass that id to `browser_xpath_query` or `browser_text_search` to extract specific
      |fragments without loading the whole page into your prompt. Call once per page; repeat after navigation.""".stripMargin,
  examples = List(
    ToolExample("Save the current page", BrowserSaveHtmlInput())
  ),
  keywords = Set("browser", "html", "save", "snapshot", "overview", "structure")
) {

  override protected def executeTyped(input: BrowserSaveHtmlInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
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
        Stream.emit[Event](BrowserToolBase.toolResult(payload, ctx))
      }
    )
}
