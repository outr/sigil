package sigil.browser.tool

import fabric.{obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolName, TypedTool}

/** Scroll the page. `direction` is `"up"` or `"down"`; `amount` is
  * `"page"` (one viewport, default), `"top"`, or `"bottom"`. */
final class BrowserScrollTool extends TypedTool[BrowserScrollInput](
  name = ToolName("browser_scroll"),
  description =
    """Scroll the page. `direction` is "up" or "down"; `amount` is "page" (one viewport), "top", or "bottom".""",
  examples = List(
    ToolExample("Scroll one viewport down", BrowserScrollInput()),
    ToolExample("Jump to the top", BrowserScrollInput(direction = "up", amount = "top")),
    ToolExample("Jump to the bottom", BrowserScrollInput(direction = "down", amount = "bottom"))
  ),
  keywords = Set("browser", "scroll", "viewport")
) {

  override protected def executeTyped(input: BrowserScrollInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      for {
        controller <- BrowserToolBase.resolveController(ctx)
        _          <- controller.run(_.eval(scrollScript(input.direction, input.amount)).unit)
      } yield Stream.emit[Event](BrowserToolBase.toolResult(
        obj("scrolled" -> str(s"${input.direction}/${input.amount}")), ctx
      ))
    )

  private def scrollScript(direction: String, amount: String): String = (direction.toLowerCase, amount.toLowerCase) match {
    case (_, "top")        => "window.scrollTo(0, 0);"
    case (_, "bottom")     => "window.scrollTo(0, document.body.scrollHeight);"
    case ("up", _)         => "window.scrollBy(0, -window.innerHeight);"
    case _                 => "window.scrollBy(0, window.innerHeight);"
  }
}
