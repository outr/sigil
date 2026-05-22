package sigil.browser.tool

import fabric.rw.*
import fabric.{obj, str}
import rapid.Task
import robobrowser.select.Selector
import sigil.TurnContext
import sigil.browser.WebBrowserMode
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

/** Click the first element matching a CSS selector. Subsequent
  * scrape / screenshot calls reflect the resulting page state. */
final class BrowserClickTool extends Tool {
  type Input  = BrowserClickInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[BrowserClickInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("browser_click")
  val description =
    "Click the first element matching the given CSS selector. Use after scraping to find selectors."
  override val examples = List(
    ToolExample("Click a button", BrowserClickInput(selector = "button.submit")),
    ToolExample("Click a link", BrowserClickInput(selector = "a.next-page"))
  )
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("browser", "click", "tap", "interact", "button")

  override def executeResult(input: BrowserClickInput,
                             ctx: TurnContext): Task[ToolResult[TextToolOutput]] =
    for {
      controller <- BrowserToolBase.resolveController(ctx)
      _          <- controller.run(_(Selector(input.selector)).click)
    } yield ToolResult.Success(BrowserToolBase.toolResult(obj("clicked" -> str(input.selector))))
}
