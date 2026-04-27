package sigil.script

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, MessageRole, ToolResults}
import sigil.signal.EventState
import sigil.tool.{JsonSchemaToDefinition, ToolExample, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Persist a new [[ScriptTool]]. The agent describes the tool's
 * surface (name, description, parameters JSON Schema) and supplies
 * the `code` body the executor will run; the framework builds the
 * record, resolves its single [[sigil.SpaceId]] via
 * [[ScriptSigil.scriptToolSpace]], and writes it to
 * `SigilDB.tools` so future turns can `find_capability` it.
 *
 * **Suggestion cascade.** After persisting, this tool emits a
 * [[ToolResults]] event whose `schemas` include
 * [[UpdateScriptToolTool]], [[DeleteScriptToolTool]], and the
 * just-created [[ScriptTool]] itself. The framework's existing
 * suggestedTools machinery surfaces these on the agent's next turn
 * with the standard one-turn decay — the natural "build → demo →
 * tweak" rhythm without sticky surface bloat.
 */
case object CreateScriptToolTool extends TypedTool[CreateScriptToolInput](
  name = ToolName("create_script_tool"),
  description =
    """Persist a new script-backed tool the agent (or any other agent in scope) can later invoke
      |through `find_capability`. The script body sees `args: fabric.Json` (matching the declared
      |`parameters` schema) and `context: TurnContext` in scope; its return value is stringified
      |and surfaced as the tool result.
      |
      |`name` must be unique — the same name overwrites. `parameters` is a JSON Schema
      |(`{"type":"object","properties":{...}}`); leave empty to accept any JSON. `space` is an
      |optional hint asking the framework to pin the tool to a specific space — the active
      |Sigil's `scriptToolSpace` policy may honor, ignore, or validate the request per
      |app-level policy.""".stripMargin,
  examples = List(
    ToolExample(
      "Persist a small derived-value computer",
      CreateScriptToolInput(
        name = "compute_total",
        description = "Sum a list of numbers; returns the total.",
        code = "args(\"values\").asVector.map(_.asDouble).sum",
        parameters = fabric.obj(
          "type" -> fabric.str("object"),
          "properties" -> fabric.obj(
            "values" -> fabric.obj(
              "type" -> fabric.str("array"),
              "items" -> fabric.obj("type" -> fabric.str("number"))
            )
          ),
          "required" -> fabric.arr(fabric.str("values"))
        )
      )
    )
  ),
  keywords = Set("create", "tool", "script", "build", "author", "register", "new")
) {
  override protected def executeTyped(input: CreateScriptToolInput,
                                      context: TurnContext): Stream[Event] = context.sigil match {
    case s: ScriptSigil =>
      Stream.force(
        s.scriptToolSpace(context.chain, input.space).flatMap { resolvedSpace =>
          val tool = ScriptTool(
            name        = ToolName(input.name),
            description = input.description,
            code        = input.code,
            parameters  = JsonSchemaToDefinition(input.parameters),
            space       = resolvedSpace,
            keywords    = input.keywords,
            createdBy   = Some(context.caller)
          )
          context.sigil.createTool(tool).map { stored =>
            val ack = Message(
              participantId  = context.caller,
              conversationId = context.conversation.id,
              topicId        = context.conversation.currentTopicId,
              content        = Vector(ResponseContent.Text(
                s"Persisted tool '${stored.name.value}' under space '${resolvedSpace.value}'."
              )),
              state          = EventState.Complete,
              role           = MessageRole.Tool,
              visibility     = MessageVisibility.Agents
            )
            val suggestion = ToolResults(
              schemas        = List(
                stored.schema,
                UpdateScriptToolTool.schema,
                DeleteScriptToolTool.schema
              ),
              participantId  = context.caller,
              conversationId = context.conversation.id,
              topicId        = context.conversation.currentTopicId,
              state          = EventState.Complete
            )
            Stream.emits[Event](List(ack, suggestion))
          }
        }.handleError { e =>
          Task.pure(Stream.emit[Event](errorReply(context, s"Failed to create tool: ${e.getMessage}")))
        }
      )
    case _ =>
      Stream.emit(errorReply(
        context,
        "Sigil instance does not mix in ScriptSigil; cannot create script tools."
      ))
  }

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

