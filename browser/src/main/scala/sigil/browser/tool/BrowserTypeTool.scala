package sigil.browser.tool

import fabric.{Str, obj, str}
import rapid.Stream
import robobrowser.select.Selector
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolName, TypedTool}

/** Type a value into the element matched by `selector`. `clearFirst`
  * clears the field first so re-runs don't append. */
final class BrowserTypeTool extends TypedTool[BrowserTypeInput](
  name = ToolName("browser_type"),
  description =
    """Type a value into the element matching the CSS selector. Sets the field's value and dispatches an `input` event so React/Vue forms react.
      |Use `clearFirst = false` to append to an existing value.""".stripMargin,
  examples = List(
    ToolExample("Type into a search box", BrowserTypeInput(selector = "input[name=q]", value = "scala"))
  ),
  keywords = Set("browser", "type", "input", "form", "fill")
) {

  override protected def executeTyped(input: BrowserTypeInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      for {
        controller <- BrowserToolBase.resolveController(ctx)
        _          <- controller.run { browser =>
                        val sel = browser(Selector(input.selector))
                        val finalValue = if (input.clearFirst) input.value
                                         else s"$${currentValue}${input.value}"
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
      } yield Stream.emit[Event](BrowserToolBase.toolResult(
        obj("typed" -> str(input.selector), "valueLength" -> fabric.num(input.value.length)), ctx
      ))
    )
}
