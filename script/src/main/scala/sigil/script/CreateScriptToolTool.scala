package sigil.script

import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility, ModeChange}
import sigil.provider.ConversationMode
import sigil.signal.EventState
import sigil.tool.{DefinitionToSchema, JsonSchemaToDefinition, ToolExample, ToolName, TypedTool}
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
  modes = Set(ScriptAuthoringMode.id),
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
  /** Append the active executor's advertised surface (Bug #54) so the
    * LLM knows which library identifiers are pre-imported and which
    * Scala-2 idioms to avoid. Without this the model writes
    * `scala.util.parsing.json.JSON` and falls into a compile-error
    * loop. */
  override def descriptionFor(mode: _root_.sigil.provider.Mode,
                              sigilInstance: _root_.sigil.Sigil): String =
    sigilInstance match {
      case s: ScriptSigil =>
        s.scriptExecutor.advertisedSurface match {
          case Some(surface) => s"${description}\n\n$surface"
          case None          => description
        }
      case _ => description
    }

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
            // Bugs #68 / #69 — emit ONE Message(Tool) carrying the
            // confirmation, the schema, and a literal invocation hint
            // so the agent can call the tool back without an extra
            // round-trip through `find_capability`. Previously this
            // tool emitted [ack, ToolResults]: two MessageRole.Tool
            // events, only the first paired with the call_id; the
            // second became a `[system: Tool result (orphan): …]`
            // frame the LLM read as ambient noise rather than as the
            // useful schema dump it actually was.
            val schemaJson = JsonFormatter.Default(DefinitionToSchema(stored.schema.input))
            val text = new StringBuilder
            text.append(s"Persisted tool '${stored.name.value}' under space '${resolvedSpace.value}'.\n\n")
            text.append("To invoke on a subsequent turn, emit a tool_call with:\n")
            text.append(s"  name: ${stored.name.value}\n")
            text.append(s"  arguments matching this schema:\n")
            text.append(schemaJson).append("\n\n")
            text.append("Note: the tool defaults to `conversation` mode affinity. The framework now\n")
            text.append("auto-pops your mode back to `conversation` so `find_capability` will surface\n")
            text.append("this tool by name on the next turn.\n\n")
            text.append("Authoring follow-ups (available in `script-authoring` mode):\n")
            text.append("  - update_script_tool — modify code, description, or parameters\n")
            text.append("  - delete_script_tool — remove the tool\n")
            val ack = Message(
              participantId  = context.caller,
              conversationId = context.conversation.id,
              topicId        = context.conversation.currentTopicId,
              content        = Vector(ResponseContent.Text(text.toString)),
              state          = EventState.Complete,
              role           = MessageRole.Tool,
              visibility     = MessageVisibility.Agents
            )
            // Bug #68 (Concern A) — auto-pop back to conversation
            // mode after a successful create. Without this the agent
            // stays in `script-authoring`, the new tool's
            // `modes = Set(ConversationMode.id)` doesn't match the
            // current mode, and `find_capability` can't surface it
            // (mode-affinity filter rejects it). The mode-pop is
            // emitted as `MessageRole.Standard` so it doesn't compete
            // with `ack` for the tool-result pairing slot.
            val modePop = ModeChange(
              mode           = ConversationMode,
              reason         = Some(s"auto-pop after create_script_tool '${stored.name.value}'"),
              participantId  = context.caller,
              conversationId = context.conversation.id,
              topicId        = context.conversation.currentTopicId,
              role           = MessageRole.Standard
            )
            Stream.emits[Event](List(ack, modePop))
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

