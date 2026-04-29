package sigil.browser.tool

import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.{Stream, Task}
import sigil.{GlobalSpace, TurnContext}
import sigil.browser.{BrowserScript, CookieJar}
import sigil.event.{Event, Message, MessageRole, MessageVisibility, ToolResults}
import sigil.signal.EventState
import sigil.tool.{JsonSchemaToDefinition, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Update an existing [[BrowserScript]] in place. Identified by
 * `name`; any omitted field keeps its stored value. The tool's
 * `space` is fixed at creation — to expose under a different space,
 * copy the script via `create_browser_script`.
 */
case object UpdateBrowserScriptTool extends TypedTool[UpdateBrowserScriptInput](
  name = ToolName("update_browser_script"),
  description =
    """Update an existing browser-script tool's description, parameters, steps, keywords, or
      |cookie-jar reference. Identified by `name`; omitted fields keep their stored value.
      |The tool's space is fixed at creation.""".stripMargin,
  keywords = Set("update", "edit", "modify", "browser", "script")
) {
  override protected def executeTyped(input: UpdateBrowserScriptInput,
                                      ctx: TurnContext): Stream[Event] = Stream.force(
    ctx.sigil.accessibleSpaces(ctx.chain).flatMap { accessible =>
      ctx.sigil.withDB(_.tools.transaction { tx =>
        tx.query.filter(_.toolName === input.name).toList.map(_.headOption).flatMap {
          case None =>
            Task.pure(Stream.emit[Event](errorReply(ctx, s"No browser script named '${input.name}'.")))
          case Some(existing: BrowserScript) =>
            if (existing.space != GlobalSpace && !accessible.contains(existing.space))
              Task.pure(Stream.emit[Event](errorReply(ctx,
                s"Browser script '${input.name}' is not accessible to this caller.")))
            else {
              val updated = existing.copy(
                description = input.description.getOrElse(existing.description),
                parameters  = input.parameters.fold(existing.parameters)(JsonSchemaToDefinition.apply),
                steps       = input.steps.getOrElse(existing.steps),
                keywords    = input.keywords.getOrElse(existing.keywords),
                cookieJarId = input.cookieJarId.map(s => Id[CookieJar](s)).orElse(existing.cookieJarId),
                modified    = Timestamp(Nowish())
              )
              tx.upsert(updated).map { stored =>
                val ack = Message(
                  participantId  = ctx.caller,
                  conversationId = ctx.conversation.id,
                  topicId        = ctx.conversation.currentTopicId,
                  content        = Vector(ResponseContent.Text(s"Updated browser script '${stored.name.value}'.")),
                  state          = EventState.Complete,
                  role           = MessageRole.Tool,
                  visibility     = MessageVisibility.Agents
                )
                val suggestion = ToolResults(
                  schemas        = List(stored.schema, UpdateBrowserScriptTool.schema, DeleteBrowserScriptTool.schema),
                  participantId  = ctx.caller,
                  conversationId = ctx.conversation.id,
                  topicId        = ctx.conversation.currentTopicId,
                  state          = EventState.Complete
                )
                Stream.emits[Event](List(ack, suggestion))
              }
            }
          case Some(_) =>
            Task.pure(Stream.emit[Event](errorReply(ctx,
              s"Tool '${input.name}' exists but is not a browser script.")))
        }
      })
    }.handleError(t => Task.pure(Stream.emit[Event](errorReply(ctx,
      s"Failed to update browser script: ${t.getMessage}"))))
  )

  private def errorReply(ctx: TurnContext, text: String): Event = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(text)),
    state          = EventState.Complete,
    role           = MessageRole.Tool,
    visibility     = MessageVisibility.Agents
  )
}
