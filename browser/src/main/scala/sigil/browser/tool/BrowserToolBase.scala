package sigil.browser.tool

import fabric.Json
import fabric.io.JsonFormatter
import rapid.Task
import sigil.TurnContext
import sigil.browser.{BrowserController, BrowserSigil}
import sigil.tool.TextToolOutput

/**
 * Shared helpers for the `sigil.browser.tool` family. Each browser
 * tool resolves the per-conversation [[BrowserController]] through
 * [[resolveController]], drives the action, then resolves to a
 * [[TextToolOutput]] carrying the tool's JSON payload — the framework
 * builds the paired result event. Page-state transitions
 * (URL/title/loading) are published separately as
 * [[sigil.browser.BrowserStateDelta]]s.
 */
private[tool] object BrowserToolBase {

  /** Resolve the [[BrowserController]] for the active conversation.
    * Errors loudly when the surrounding `Sigil` doesn't mix in
    * [[BrowserSigil]] — tells the app to add the trait rather than
    * silently failing the tool. */
  def resolveController(ctx: TurnContext): Task[BrowserController] =
    ctx.sigil match {
      case bs: BrowserSigil =>
        bs.browserController(ctx.conversation.id, ctx.caller, ctx.chain)
      case _ =>
        Task.error(new IllegalStateException(
          "Browser tools require BrowserSigil — mix `BrowserSigil` into your Sigil class."))
    }

  /** Wrap a JSON payload as a [[TextToolOutput]] — the browser
    * family's result is the compact-rendered JSON the agent reads. */
  def toolResult(payload: Json): TextToolOutput =
    TextToolOutput(JsonFormatter.Compact(payload))
}
