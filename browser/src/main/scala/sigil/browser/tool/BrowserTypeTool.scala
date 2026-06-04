package sigil.browser.tool

import fabric.rw.*
import fabric.{Str, obj, str}
import rapid.Task
import robobrowser.select.Selector
import sigil.tool.ToolContext
import sigil.browser.WebBrowserMode
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

/**
 * Type a value into the element matched by `selector`. `clearFirst`
 * clears the field first so re-runs don't append.
 */
final class BrowserTypeTool extends Tool {
  type Input = BrowserTypeInput
  type Output = TextToolOutput
  val inputRW = summon[RW[BrowserTypeInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("browser_type")
  val description =
    """Type a value into the element matching the CSS selector. Sets the field's value and dispatches an `input` event so React/Vue forms react.
      |Use `clearFirst = false` to append to an existing value.""".stripMargin
  override val examples = List(
    ToolExample("Type into a search box", BrowserTypeInput(selector = "input[name=q]", value = "scala"))
  )
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("browser", "type", "input", "form", "fill")

  override def executeResult(input: BrowserTypeInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    for {
      controller <- BrowserToolBase.resolveController(ctx)
      _ <- controller.run { browser =>
        val sel = browser(Selector(input.selector))
        // Selection.value(Json) sets value + fires input event.
        // Clear-first is the default semantic; appending requires
        // a JS evaluate that reads first.
        if (input.clearFirst) sel.value(Str(input.value))
        else browser.eval(
          s"""const els = document.querySelectorAll("${input.selector}");
                           |els.forEach(el => {
                           |  el.value = (el.value || '') + ${fabric.io.JsonFormatter.Compact(Str(input.value))};
                           |  el.dispatchEvent(new Event('input', { bubbles: true }));
                           |});""".stripMargin
        ).unit
      }
    } yield ToolResult.Success(BrowserToolBase.toolResult(
      obj("typed" -> str(input.selector), "valueLength" -> fabric.num(input.value.length))
    ))
}
