package sigil.script

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DefinitionToSchema, DiscoverySpec, Effect, JsonSchemaToDefinition, MutationTargeting, TextToolOutput, Tool, ToolName, ToolProfile, ToolResult, ToolSpec}

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
 * The result text confirms the update and carries the
 * (possibly-updated) tool's invocation shape and JSON schema.
 */
case object UpdateScriptToolTool extends Tool {
  type Input  = UpdateScriptToolInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[UpdateScriptToolInput]]
  val outputRW = summon[RW[TextToolOutput]]

  override val name = ToolName("update_script_tool")
  override val description =
    """Update the body, description, parameters schema, or keywords of an existing script-backed
      |tool. Identified by `name`; any omitted field keeps its stored value. The tool's space is
      |fixed at creation — copy the tool to surface it under a different space.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(
      keywords = Set("update", "edit", "modify", "tool", "script", "change"),
      modes = Set(ScriptAuthoringMode.id)
    )
  )

  override def executeResult(input: UpdateScriptToolInput,
                             context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { accessible =>
      context.sigil.withDB(_.tools.transaction { tx =>
        tx.query.filter(_.toolName === input.name).toList.map(_.headOption).flatMap {
          case None =>
            Task.pure(ToolResult.failure(s"No script tool named '${input.name}' found."))
          case Some(existing: ScriptTool) =>
            if (existing.space != sigil.GlobalSpace && !accessible.contains(existing.space)) {
              Task.pure(ToolResult.failure(s"Tool '${input.name}' is not accessible to this caller."))
            } else {
              val updated = existing.copy(
                description = input.description.getOrElse(existing.description),
                code        = input.code.getOrElse(existing.code),
                parameters  = input.parameters.fold(existing.parameters)(JsonSchemaToDefinition.apply),
                keywords    = input.keywords.getOrElse(existing.keywords),
                modified    = Timestamp(Nowish())
              )
              tx.upsert(updated).map { stored =>
                val schemaJson = JsonFormatter.Default(DefinitionToSchema(stored.schema.input))
                val text = new StringBuilder
                text.append(s"Updated tool '${stored.name.value}'.\n\n")
                text.append("Current invocation shape:\n")
                text.append(s"  name: ${stored.name.value}\n")
                text.append(s"  arguments matching this schema:\n")
                text.append(schemaJson).append("\n")
                ToolResult.Success(TextToolOutput(text.toString))
              }
            }
          case Some(_) =>
            Task.pure(ToolResult.failure(s"Tool '${input.name}' exists but is not a script tool."))
        }
      })
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Failed to update tool: ${e.getMessage}"))
    }
}
