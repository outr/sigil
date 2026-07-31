package sigil.browser.tool

import fabric.rw.*
import fabric.{obj, str}
import rapid.Task
import sigil.tool.ToolContext
import sigil.browser.{ScrollAmount, ScrollDirection, WebBrowserMode}
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolExample,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

/**
 * Scroll the page. `direction` chooses up / down; `amount` chooses a
 * one-viewport page move or an absolute top / bottom jump.
 */
final class BrowserScrollTool extends Tool {
  type Input = BrowserScrollInput
  type Output = TextToolOutput
  val io: ToolIO[BrowserScrollInput, TextToolOutput] = ToolIO.derived[BrowserScrollInput, TextToolOutput].withExamples(
    ToolExample("Scroll one viewport down", BrowserScrollInput()),
    ToolExample("Jump to the top", BrowserScrollInput(direction = ScrollDirection.Up, amount = ScrollAmount.Top)),
    ToolExample("Jump to the bottom", BrowserScrollInput(direction = ScrollDirection.Down, amount = ScrollAmount.Bottom))
  )

  override val name = ToolName("browser_scroll")
  override val description =
    "Scroll the page. `direction` is up or down; `amount` is page (one viewport), top, or bottom."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(
      keywords = Set("browser", "scroll", "viewport"),
      modes = Set(WebBrowserMode.id)
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: BrowserScrollInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    for {
      controller <- BrowserToolBase.resolveController(ctx)
      _ <- controller.run(_.eval(scrollScript(input.direction, input.amount)).unit)
    } yield ToolResult.Success(BrowserToolBase.toolResult(
      obj("scrolled" -> str(s"${input.direction}/${input.amount}"))
    ))

  private def scrollScript(direction: ScrollDirection, amount: ScrollAmount): String = (direction, amount) match {
    case (_, ScrollAmount.Top) => "window.scrollTo(0, 0);"
    case (_, ScrollAmount.Bottom) => "window.scrollTo(0, document.body.scrollHeight);"
    case (ScrollDirection.Up, _) => "window.scrollBy(0, -window.innerHeight);"
    case (ScrollDirection.Down, _) => "window.scrollBy(0, window.innerHeight);"
  }
}
