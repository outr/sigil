package sigil.browser.tool

import fabric.{obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.browser.{ScrollAmount, ScrollDirection, WebBrowserMode}
import sigil.event.Event
import sigil.tool.{ToolExample, ToolName, TypedTool}

/** Scroll the page. `direction` chooses up / down; `amount` chooses a
  * one-viewport page move or an absolute top / bottom jump. */
final class BrowserScrollTool extends TypedTool[BrowserScrollInput](
  name = ToolName("browser_scroll"),
  description =
    "Scroll the page. `direction` is up or down; `amount` is page (one viewport), top, or bottom.",
  examples = List(
    ToolExample("Scroll one viewport down", BrowserScrollInput()),
    ToolExample("Jump to the top", BrowserScrollInput(direction = ScrollDirection.Up, amount = ScrollAmount.Top)),
    ToolExample("Jump to the bottom", BrowserScrollInput(direction = ScrollDirection.Down, amount = ScrollAmount.Bottom))
  ),
  modes = Set(WebBrowserMode.id),
  keywords = Set("browser", "scroll", "viewport")
) {
  override def paginate: Boolean = false


  override protected def executeTyped(input: BrowserScrollInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      for {
        controller <- BrowserToolBase.resolveController(ctx)
        _          <- controller.run(_.eval(scrollScript(input.direction, input.amount)).unit)
      } yield Stream.emit[Event](BrowserToolBase.toolResult(
        obj("scrolled" -> str(s"${input.direction}/${input.amount}")), ctx
      ))
    )

  private def scrollScript(direction: ScrollDirection, amount: ScrollAmount): String = (direction, amount) match {
    case (_, ScrollAmount.Top)        => "window.scrollTo(0, 0);"
    case (_, ScrollAmount.Bottom)     => "window.scrollTo(0, document.body.scrollHeight);"
    case (ScrollDirection.Up, _)      => "window.scrollBy(0, -window.innerHeight);"
    case (ScrollDirection.Down, _)    => "window.scrollBy(0, window.innerHeight);"
  }
}
