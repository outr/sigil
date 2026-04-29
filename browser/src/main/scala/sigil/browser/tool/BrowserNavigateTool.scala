package sigil.browser.tool

import fabric.{num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.browser.BrowserStateDelta
import sigil.event.Event
import sigil.tool.{ToolExample, ToolName, TypedTool}

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
final class BrowserNavigateTool extends TypedTool[BrowserNavigateInput](
  name = ToolName("browser_navigate"),
  description =
    """Navigate the headless browser to a URL. Waits for the page's load event, then runs RoboBrowser's
      |shadow-DOM fix so subsequent XPath queries see content inside web components.
      |Returns the final URL and `<title>` so the agent knows what's on screen.
      |Use as the first action in any browser task; follow with `browser_save_html` to persist the page
      |for structural querying via `browser_xpath_query` / `browser_text_search`.""".stripMargin,
  examples = List(
    ToolExample("Open a homepage", BrowserNavigateInput(url = "https://example.com/")),
    ToolExample("Open with a longer wait for slow pages",
      BrowserNavigateInput(url = "https://news.example/", waitForLoadSeconds = 30))
  ),
  keywords = Set("browser", "navigate", "open", "goto", "load", "url")
) {

  override protected def executeTyped(input: BrowserNavigateInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
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
        Stream.emit[Event](BrowserToolBase.toolResult(payload, ctx))
      }
    )
}
