package sigil.browser.tool

import fabric.Json
import fabric.io.JsonFormatter
import rapid.Task
import sigil.TurnContext
import sigil.browser.{BrowserController, BrowserSigil, BrowserStateDelta}
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Shared helpers for the `sigil.browser.tool` family. Each browser
 * tool resolves the per-conversation [[BrowserController]] through
 * [[resolveController]], drives the action, then emits one
 * `Message(role = Tool)` carrying the tool's JSON output and one
 * [[BrowserStateDelta]] reflecting the URL/title/loading transition.
 *
 * Tools that don't change page state (e.g. a future
 * `browser_get_cookies`) skip the delta and emit only the tool result.
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

  /** Build a `Message(role = Tool)` carrying the tool's JSON
    * payload as a single Text content block. Mirrors the
    * `FsToolEmit` shape used by `sigil.tool.fs` so all tool
    * results render uniformly. */
  def toolResult(payload: Json, ctx: TurnContext): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
    state          = EventState.Complete,
    role           = MessageRole.Tool
  )
}
