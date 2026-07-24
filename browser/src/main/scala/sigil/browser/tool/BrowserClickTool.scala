package sigil.browser.tool

import fabric.rw.*
import fabric.{num, obj, str}
import rapid.Task
import robobrowser.select.Selector
import sigil.tool.ToolContext
import sigil.browser.WebBrowserMode
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

/**
 * Click the first element matching a CSS selector. Subsequent
 * scrape / screenshot calls reflect the resulting page state.
 */
final class BrowserClickTool extends Tool {
  type Input = BrowserClickInput
  type Output = TextToolOutput
  val inputRW = summon[RW[BrowserClickInput]]
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
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    for {
      controller <- BrowserToolBase.resolveController(ctx)
      result <- controller.run[ToolResult[TextToolOutput]] { browser =>
        val sel = browser(Selector(input.selector))
        sel.count.flatMap {
          case 0 =>
            Task.pure(ToolResult.failure(
              s"No element matches selector '${input.selector}' — nothing was clicked",
              hint = Some("Save the page with browser_save_html, then use browser_text_search / " +
                "browser_xpath_query to find a selector that actually exists before clicking.")
            ))
          case n =>
            sel.click.map { _ =>
              ToolResult.Success(BrowserToolBase.toolResult(
                obj("clicked" -> str(input.selector), "matched" -> num(n))
              ))
            }
        }
      }
    } yield result
}
