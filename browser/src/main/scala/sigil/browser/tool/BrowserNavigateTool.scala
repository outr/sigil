package sigil.browser.tool

import fabric.rw.*
import fabric.{num, obj, str}
import rapid.Task
import sigil.tool.ToolContext
import sigil.browser.BrowserStateDelta
import sigil.browser.WebBrowserMode
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

import scala.concurrent.duration.*

/**
 * Navigate the per-conversation [[sigil.browser.BrowserController]]
 * to a URL and wait for the page's `load` event (capped at
 * `waitForLoadSeconds`).
 *
 * Emits a `Message(role = Tool)` describing the navigation outcome
 * (final URL, page title). The URL/title transition is published
 * separately as a [[BrowserStateDelta]] via `Sigil.publish` so
 * streaming subscribers see the page state update without polling.
 */
final class BrowserNavigateTool extends Tool {
  type Input  = BrowserNavigateInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[BrowserNavigateInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("browser_navigate")
  val description =
    """Navigate the headless browser to a URL. Waits for the page's load event, then runs RoboBrowser's
      |shadow-DOM fix so subsequent XPath queries see content inside web components.
      |Returns the final URL and `<title>` so the agent knows what's on screen.
      |Use as the first action in any browser task; follow with `browser_save_html` to persist the page
      |for structural querying via `browser_xpath_query` / `browser_text_search`.""".stripMargin
  override val examples = List(
    ToolExample("Open a homepage", BrowserNavigateInput(url = "https://example.com/")),
    ToolExample("Open with a longer wait for slow pages",
      BrowserNavigateInput(url = "https://news.example/", waitForLoadSeconds = 30))
  )
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("browser", "navigate", "open", "goto", "load", "url")

  override def executeResult(input: BrowserNavigateInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    for {
      controller <- BrowserToolBase.resolveController(ctx)
      title      <- controller.run { browser =>
                      for {
                        _ <- browser.navigate(input.url)
                        _ <- browser.waitForLoaded(timeout = input.waitForLoadSeconds.seconds)
                        // Pierce shadow DOMs so XPath queries see web-component content.
                        // Failures are non-fatal — pages without shadow roots simply no-op.
                        _ <- browser.shadowDOMFix().handleError(_ => rapid.Task.unit)
                        t <- browser.title
                      } yield t
                    }
      // Publish the BrowserStateDelta as a side effect — the framework
      // routes it through the signal hub + persists the mutation
      // against the controller's BrowserState target.
      _          <- ctx.sigil.publish(BrowserStateDelta(
                      target         = controller.stateId,
                      conversationId = ctx.conversation.id,
                      url            = Some(input.url),
                      title          = Some(title),
                      loading        = Some(false)
                    ))
    } yield {
      val payload = obj(
        "url"   -> str(input.url),
        "title" -> str(title),
        "wait"  -> num(input.waitForLoadSeconds)
      )
      ToolResult.Success(BrowserToolBase.toolResult(payload))
    }
}
