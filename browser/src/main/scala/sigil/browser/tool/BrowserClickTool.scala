package sigil.browser.tool

import fabric.{obj, str}
import rapid.Stream
import robobrowser.select.Selector
import sigil.TurnContext
import sigil.browser.WebBrowserMode
import sigil.event.Event
import sigil.tool.{ToolExample, ToolName, TypedTool}

/** Click the first element matching a CSS selector. Subsequent
  * scrape / screenshot calls reflect the resulting page state. */
final class BrowserClickTool extends TypedTool[BrowserClickInput](
  name = ToolName("browser_click"),
  description =
    "Click the first element matching the given CSS selector. Use after scraping to find selectors.",
  examples = List(
    ToolExample("Click a button", BrowserClickInput(selector = "button.submit")),
    ToolExample("Click a link", BrowserClickInput(selector = "a.next-page"))
  ),
  modes = Set(WebBrowserMode.id),
  keywords = Set("browser", "click", "tap", "interact", "button")
) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: BrowserClickInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      for {
        controller <- BrowserToolBase.resolveController(ctx)
        _          <- controller.run(_(Selector(input.selector)).click)
      } yield Stream.emit[Event](BrowserToolBase.toolResult(
        obj("clicked" -> str(input.selector)), ctx
      ))
    )
}
