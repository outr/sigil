package sigil.browser.tool

import rapid.{Stream, Task}
import sigil.{GlobalSpace, TurnContext}
import sigil.browser.BrowserScript
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/** Delete a stored [[BrowserScript]] by name. Authz: caller's
  * `accessibleSpaces` must include the script's space (or the
  * script must live in [[GlobalSpace]]). */
case object DeleteBrowserScriptTool extends TypedTool[DeleteBrowserScriptInput](
  name = ToolName("delete_browser_script"),
  description =
    "Delete a stored browser-script tool by name. Caller must have access to the script's space.",
  keywords = Set("delete", "remove", "browser", "script")
) {
  override protected def executeTyped(input: DeleteBrowserScriptInput,
                                      ctx: TurnContext): Stream[Event] = Stream.force(
    ctx.sigil.accessibleSpaces(ctx.chain).flatMap { accessible =>
      ctx.sigil.withDB(_.tools.transaction { tx =>
        tx.query.filter(_.toolName === input.name).toList.map(_.headOption).flatMap {
          case None =>
            Task.pure(Stream.emit[Event](reply(ctx, s"No browser script named '${input.name}'.")))
          case Some(existing: BrowserScript) =>
            if (existing.space != GlobalSpace && !accessible.contains(existing.space))
              Task.pure(Stream.emit[Event](reply(ctx,
                s"Browser script '${input.name}' is not accessible to this caller.")))
            else
              tx.delete(existing._id).map { _ =>
                Stream.emit[Event](reply(ctx, s"Deleted browser script '${input.name}'."))
              }
          case Some(_) =>
            Task.pure(Stream.emit[Event](reply(ctx,
              s"Tool '${input.name}' exists but is not a browser script.")))
        }
      })
    }.handleError(t => Task.pure(Stream.emit[Event](reply(ctx,
      s"Failed to delete browser script: ${t.getMessage}"))))
  )

  private def reply(ctx: TurnContext, text: String): Event = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(text)),
    state          = EventState.Complete,
    role           = MessageRole.Tool,
    visibility     = MessageVisibility.Agents
  )
}
