package sigil.script

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, TextToolOutput, Tool, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * List the script-backed tools visible to the caller. Visibility is
 * [[sigil.GlobalSpace]] ∪ [[sigil.Sigil.accessibleSpaces]] for the
 * current chain — the same predicate `find_capability` uses. Output
 * is a single Markdown listing of each tool's name, description, and
 * space.
 */
case object ListScriptToolsTool extends Tool {
  type Input = ListScriptToolsInput
  type Output = TextToolOutput
  val io: ToolIO[ListScriptToolsInput, TextToolOutput] = ToolIO.derived[ListScriptToolsInput, TextToolOutput]

  override val name = ToolName("list_script_tools")
  override val description =
    """Enumerate the script-backed tools the caller can currently see (their own scoped tools
      |plus globally available ones). Optional `nameContains` narrows the listing to tools
      |whose name contains the given substring.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("list", "tools", "script", "enumerate", "available"),
      modes = Set(ScriptAuthoringMode.id)
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ListScriptToolsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { accessible =>
      context.sigil.withDB(_.tools.transaction(_.list)).map { allTools =>
        val needle = input.nameContains.map(_.toLowerCase)
        val visible = allTools.collect { case s: ScriptTool => s }.filter { t =>
          val isVisible = t.space == sigil.GlobalSpace || accessible.contains(t.space)
          val matchesNeedle = needle.forall(n => t.name.value.toLowerCase.contains(n))
          isVisible && matchesNeedle
        }.sortBy(_.name.value)
        ToolResult.Success(TextToolOutput(renderListing(visible)))
      }
    }

  private def renderListing(tools: List[ScriptTool]): String =
    if (tools.isEmpty) "No script tools visible."
    else tools.map(t =>
      s"- **${t.name.value}** _(space: ${t.space.value})_ — ${t.description}").mkString("\n")
}
