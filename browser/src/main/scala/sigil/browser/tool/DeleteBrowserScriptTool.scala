package sigil.browser.tool

import fabric.rw.*
import rapid.Task
import sigil.browser.BrowserScript
import sigil.browser.WebBrowserMode
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}
import sigil.GlobalSpace
import sigil.tool.ToolContext

/**
 * Delete a stored [[BrowserScript]] by name. Authz: caller's
 * `accessibleSpaces` must include the script's space (or the
 * script must live in [[GlobalSpace]]).
 */
case object DeleteBrowserScriptTool extends Tool {
  type Input = DeleteBrowserScriptInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DeleteBrowserScriptInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("delete_browser_script")
  val description =
    "Delete a stored browser-script tool by name. Caller must have access to the script's space."
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("delete", "remove", "browser", "script")

  override def executeResult(input: DeleteBrowserScriptInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    ctx.sigil.accessibleSpaces(ctx.chain).flatMap { accessible =>
      ctx.sigil.withDB(_.tools.transaction { tx =>
        tx.query.filter(_.toolName === input.name).toList.map(_.headOption).flatMap {
          case None =>
            Task.pure(ToolResult.failure(s"No browser script named '${input.name}'."))
          case Some(existing: BrowserScript) =>
            if (existing.space != GlobalSpace && !accessible.contains(existing.space))
              Task.pure(ToolResult.failure(
                s"Browser script '${input.name}' is not accessible to this caller."))
            else
              tx.delete(existing._id).map { _ =>
                ToolResult.Success(TextToolOutput(s"Deleted browser script '${input.name}'."))
              }
          case Some(_) =>
            Task.pure(ToolResult.failure(
              s"Tool '${input.name}' exists but is not a browser script."))
        }
      })
    }.handleError(t =>
      Task.pure(ToolResult.failure(
        s"Failed to delete browser script: ${t.getMessage}")))
}
