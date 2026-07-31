package sigil.browser.tool

import fabric.rw.*
import fabric.{Str, num, obj, str}
import rapid.Task
import robobrowser.select.Selector
import sigil.tool.ToolContext
import sigil.browser.WebBrowserMode
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolExample, ToolName, ToolProfile, ToolResult, ToolSpec}

/** Type a value into the element matched by `selector`. `clearFirst`
  * clears the field first so re-runs don't append. */
final class BrowserTypeTool extends Tool {
  type Input  = BrowserTypeInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[BrowserTypeInput]]
  val outputRW = summon[RW[TextToolOutput]]

  override val name = ToolName("browser_type")
  override val description =
    """Type a value into the element matching the CSS selector. Sets the field's value and dispatches an `input` event so React/Vue forms react.
      |Use `clearFirst = false` to append to an existing value.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(
      keywords = Set("browser", "type", "input", "form", "fill"),
      modes = Set(WebBrowserMode.id)
    )
  )
  override val examples = List(
    ToolExample("Type into a search box", BrowserTypeInput(selector = "input[name=q]", value = "scala"))
  )

  override def executeResult(input: BrowserTypeInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    for {
      controller <- BrowserToolBase.resolveController(ctx)
      result     <- controller.run[ToolResult[TextToolOutput]] { browser =>
                      val sel = browser(Selector(input.selector))
                      sel.count.flatMap {
                        case 0 =>
                          Task.pure(ToolResult.failure(
                            s"No element matches selector '${input.selector}' — nothing was typed",
                            hint = Some("Save the page with browser_save_html, then use browser_text_search / " +
                              "browser_xpath_query to find an input/textarea selector that actually exists.")
                          ))
                        case n =>
                          // Selection.value(Json) sets value + fires input event on every match.
                          // Clear-first is the default semantic; appending requires a JS evaluate
                          // that reads the existing value first.
                          val fill =
                            if (input.clearFirst) sel.value(Str(input.value))
                            else browser.eval(
                              s"""const els = document.querySelectorAll("${input.selector}");
                                 |els.forEach(el => {
                                 |  el.value = (el.value || '') + ${fabric.io.JsonFormatter.Compact(Str(input.value))};
                                 |  el.dispatchEvent(new Event('input', { bubbles: true }));
                                 |});""".stripMargin
                            ).unit
                          fill.map { _ =>
                            ToolResult.Success(BrowserToolBase.toolResult(
                              obj("typed" -> str(input.selector), "filled" -> num(n), "valueLength" -> num(input.value.length))
                            ))
                          }
                      }
                    }
    } yield result
}
