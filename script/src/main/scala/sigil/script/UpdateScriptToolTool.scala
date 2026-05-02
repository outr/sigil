package sigil.script

import fabric.io.JsonFormatter
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{DefinitionToSchema, JsonSchemaToDefinition, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Modify an existing [[ScriptTool]] in-place. Looks the record up by
 * `name`; rejects if the caller's [[sigil.Sigil.accessibleSpaces]]
 * doesn't include the tool's `space` (or the space isn't
 * [[sigil.GlobalSpace]]).
 *
 * The record's `space` is immutable — set at creation time, never
 * changed by an update. Apps that want to "move" a tool to a
 * different space create a copy.
 *
 * Emits a [[ToolResults]] suggesting itself again (most edits cluster)
 * plus [[DeleteScriptToolTool]] (in case the user gives up) and the
 * just-touched tool's own schema (to demo the result).
 */
case object UpdateScriptToolTool extends TypedTool[UpdateScriptToolInput](
  name = ToolName("update_script_tool"),
  description =
    """Update the body, description, parameters schema, or keywords of an existing script-backed
      |tool. Identified by `name`; any omitted field keeps its stored value. The tool's space is
      |fixed at creation — copy the tool to surface it under a different space.""".stripMargin,
  modes = Set(ScriptAuthoringMode.id),
  keywords = Set("update", "edit", "modify", "tool", "script", "change")
) {
  override protected def executeTyped(input: UpdateScriptToolInput,
                                      context: TurnContext): Stream[Event] = Stream.force(
    context.sigil.accessibleSpaces(context.chain).flatMap { accessible =>
      context.sigil.withDB(_.tools.transaction { tx =>
        tx.query.filter(_.toolName === input.name).toList.map(_.headOption).flatMap {
          case None =>
            Task.pure(Stream.emit[Event](errorReply(
              context, s"No script tool named '${input.name}' found."
            )))
          case Some(existing: ScriptTool) =>
            if (existing.space != sigil.GlobalSpace && !accessible.contains(existing.space)) {
              Task.pure(Stream.emit[Event](errorReply(
                context, s"Tool '${input.name}' is not accessible to this caller."
              )))
            } else {
              val updated = existing.copy(
                description = input.description.getOrElse(existing.description),
                code        = input.code.getOrElse(existing.code),
                parameters  = input.parameters.fold(existing.parameters)(JsonSchemaToDefinition.apply),
                keywords    = input.keywords.getOrElse(existing.keywords),
                modified    = Timestamp(Nowish())
              )
              tx.upsert(updated).map { stored =>
                // Bug #69 — single Message(Tool) carrying the
                // confirmation + the (possibly-updated) schema +
                // invocation hint. Replaces the previous
                // [ack, ToolResults] cascade whose ToolResults event
                // landed orphan-framed because two MessageRole.Tool
                // events from one executeTyped can't both pair with
                // the same call_id.
                val schemaJson = JsonFormatter.Default(DefinitionToSchema(stored.schema.input))
                val text = new StringBuilder
                text.append(s"Updated tool '${stored.name.value}'.\n\n")
                text.append("Current invocation shape:\n")
                text.append(s"  name: ${stored.name.value}\n")
                text.append(s"  arguments matching this schema:\n")
                text.append(schemaJson).append("\n")
                val ack = Message(
                  participantId  = context.caller,
                  conversationId = context.conversation.id,
                  topicId        = context.conversation.currentTopicId,
                  content        = Vector(ResponseContent.Text(text.toString)),
                  state          = EventState.Complete,
                  role           = MessageRole.Tool,
                  visibility     = MessageVisibility.Agents
                )
                Stream.emit[Event](ack)
              }
            }
          case Some(_) =>
            Task.pure(Stream.emit[Event](errorReply(
              context, s"Tool '${input.name}' exists but is not a script tool."
            )))
        }
      })
    }.handleError { e =>
      Task.pure(Stream.emit[Event](errorReply(context, s"Failed to update tool: ${e.getMessage}")))
    }
  )

  private def errorReply(context: TurnContext, text: String): Event =
    Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      visibility     = MessageVisibility.Agents
    )
}
