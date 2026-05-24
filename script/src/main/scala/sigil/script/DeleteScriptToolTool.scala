package sigil.script

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}

/**
 * Remove an existing [[ScriptTool]]. Looks the record up by `name`;
 * rejects if the caller's [[sigil.Sigil.accessibleSpaces]] doesn't
 * include the tool's `space` (or the space isn't
 * [[sigil.GlobalSpace]]). No suggestion cascade — once a tool is
 * deleted there's nothing to act on.
 */
case object DeleteScriptToolTool extends Tool {
  type Input  = DeleteScriptToolInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[DeleteScriptToolInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("delete_script_tool")
  val description =
    """Remove a previously created script-backed tool. Identified by `name`. Permission is
      |denied if the caller doesn't have access to the tool's space.""".stripMargin
  override val modes = Set(ScriptAuthoringMode.id)
  override val keywords = Set("delete", "remove", "tool", "script", "drop")

  override def executeResult(input: DeleteScriptToolInput,
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
              tx.delete(existing._id).map { _ =>
                ToolResult.Success(TextToolOutput(s"Deleted tool '${existing.name.value}'."))
              }
            }
          case Some(_) =>
            Task.pure(ToolResult.failure(s"Tool '${input.name}' exists but is not a script tool."))
        }
      })
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Failed to delete tool: ${e.getMessage}"))
    }
}
