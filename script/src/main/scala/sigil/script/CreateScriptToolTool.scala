package sigil.script

import fabric.io.JsonFormatter
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.{MessageRole, ModeChange}
import sigil.provider.ConversationMode
import sigil.tool.{DefinitionToSchema, DiscoverySpec, Effect, JsonSchemaToDefinition, MutationTargeting, TextToolOutput, Tool, ToolExample, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Persist a new [[ScriptTool]]. The agent describes the tool's
 * surface (name, description, parameters JSON Schema) and supplies
 * the `code` body the executor will run; the framework builds the
 * record, resolves its single [[sigil.SpaceId]] via
 * [[ScriptSigil.scriptToolSpace]], and writes it to
 * `SigilDB.tools` so future turns can `find_capability` it.
 *
 * After persisting, the tool is pinned to the conversation as
 * `Active` (so the agent's next turn's roster includes it without a
 * `find_capability` round-trip), the result text carries the
 * just-created tool's invocation schema, and an auto-pop
 * [[ModeChange]] back to `conversation` mode is emitted as an
 * ancillary event.
 */
case object CreateScriptToolTool extends Tool {
  type Input  = CreateScriptToolInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[CreateScriptToolInput]]
  val outputRW = summon[RW[TextToolOutput]]

  override val name = ToolName("create_script_tool")
  override val description =
    """Persist a new script-backed tool the agent (or any other agent in scope) can later invoke
      |through `find_capability`. The script body sees `args: fabric.Json` (matching the declared
      |`parameters` schema) and `context: ToolContext` in scope; its return value is stringified
      |and surfaced as the tool result.
      |
      |`name` must be unique — the same name overwrites. `parameters` is a JSON Schema
      |(`{"type":"object","properties":{...}}`); leave empty to accept any JSON. `space` is an
      |optional hint asking the framework to pin the tool to a specific space — the active
      |Sigil's `scriptToolSpace` policy may honor, ignore, or validate the request per
      |app-level policy.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(
      keywords = Set("create", "tool", "script", "build", "author", "register", "new"),
      modes = Set(ScriptAuthoringMode.id)
    )
  )
  override val examples = List(
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
  )

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

  override def executeResult(input: CreateScriptToolInput,
                             context: ToolContext): Task[ToolResult[TextToolOutput]] = context.sigil match {
    case s: ScriptSigil =>
      ToolName.parse(input.name) match {
        case Left(reason) =>
          Task.pure(ToolResult.failure(
            message = s"Invalid script tool name '${input.name}': $reason",
            hint = Some(s"Pick a name matching ${ToolName.DynamicGrammar} and retry.")
          ))
        case Right(toolName) =>
          s.scriptToolSpace(context.chain, input.space).flatMap { resolvedSpace =>
            val tool = ScriptTool(
              name        = toolName,
              description = input.description,
              code        = input.code,
              parameters  = JsonSchemaToDefinition(input.parameters),
              space       = resolvedSpace,
              keywords    = input.keywords,
              createdBy   = Some(context.caller)
            )
            context.sigil.createTool(tool).flatMap { stored =>
              // Pin the just-created tool to this conversation as
              // Active so the agent's next turn's roster includes it
              // directly — no find_capability round-trip needed.
              val overlayTask = context.sigil.addConversationToolOverlay(
                _root_.sigil.conversation.ConversationToolOverlay(
                  conversationId = context.conversation.id,
                  source         = s"create_script_tool:${stored.name.value}",
                  policy         = _root_.sigil.provider.ToolPolicy.Active(List(stored.name))
                )
              ).handleError { t =>
                Task(scribe.warn(s"create_script_tool: ConversationToolOverlay install failed: ${t.getMessage}"))
              }
              // Auto-pop back to conversation mode after a successful
              // create — the agent's intent in script-authoring was to
              // build the tool, and the user typically wants to use it
              // back in conversation. Emitted as an ancillary event;
              // it is not this tool's result.
              val modePop = ModeChange(
                mode           = ConversationMode,
                reason         = Some(s"auto-pop after create_script_tool '${stored.name.value}'"),
                participantId  = context.caller,
                conversationId = context.conversation.id,
                topicId        = context.conversation.currentTopicId,
                role           = MessageRole.Standard
              )
              overlayTask.flatMap(_ => context.emit(modePop)).map { _ =>
                // The result text carries the confirmation, the schema,
                // and a literal invocation hint so the agent can call
                // the tool back without an extra `find_capability`
                // round-trip.
                val schemaJson = JsonFormatter.Default(DefinitionToSchema(stored.schema.input))
                val text = new StringBuilder
                text.append(s"Persisted tool '${stored.name.value}' under space '${resolvedSpace.value}'.\n\n")
                text.append("To invoke on a subsequent turn, emit a tool_call with:\n")
                text.append(s"  name: ${stored.name.value}\n")
                text.append(s"  arguments matching this schema:\n")
                text.append(schemaJson).append("\n\n")
                text.append("The tool is pinned to this conversation — call it directly on your next turn;\n")
                text.append("no `find_capability` round-trip needed. Mode is auto-popped back to\n")
                text.append("`conversation` so the response flow continues normally.\n\n")
                text.append("Authoring follow-ups (available in `script-authoring` mode):\n")
                text.append("  - update_script_tool — modify code, description, or parameters\n")
                text.append("  - delete_script_tool — remove the tool\n")
                ToolResult.Success(TextToolOutput(text.toString))
              }
            }
          }.handleError { e =>
            Task.pure(ToolResult.failure(s"Failed to create tool: ${e.getMessage}"))
          }
      }
    case _ =>
      Task.pure(ToolResult.failure(
        "Sigil instance does not mix in ScriptSigil; cannot create script tools."
      ))
  }
}

