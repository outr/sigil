package sigil.browser.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.Task
import sigil.browser.WebBrowserMode
import sigil.browser.{BrowserScript, CookieJar}
import sigil.tool.{DefinitionToSchema, JsonSchemaToDefinition, TextToolOutput, Tool, ToolName, ToolResult}
import sigil.GlobalSpace
import sigil.tool.ToolContext

/**
 * Update an existing [[BrowserScript]] in place. Identified by
 * `name`; any omitted field keeps its stored value. The tool's
 * `space` is fixed at creation — to expose under a different space,
 * copy the script via `create_browser_script`.
 */
case object UpdateBrowserScriptTool extends Tool {
  type Input = UpdateBrowserScriptInput
  type Output = TextToolOutput
  val inputRW = summon[RW[UpdateBrowserScriptInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("update_browser_script")
  val description =
    """Update an existing browser-script tool's description, parameters, steps, keywords, or
      |cookie-jar reference. Identified by `name`; omitted fields keep their stored value.
      |The tool's space is fixed at creation.""".stripMargin
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("update", "edit", "modify", "browser", "script")

  override def executeResult(input: UpdateBrowserScriptInput,
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
            else {
              val updated = existing.copy(
                description = input.description.getOrElse(existing.description),
                parameters = input.parameters.fold(existing.parameters)(JsonSchemaToDefinition.apply),
                steps = input.steps.getOrElse(existing.steps),
                keywords = input.keywords.getOrElse(existing.keywords),
                cookieJarId = input.cookieJarId.map(s => Id[CookieJar](s)).orElse(existing.cookieJarId),
                modified = Timestamp(Nowish())
              )
              tx.upsert(updated).map { stored =>
                val schemaJson = JsonFormatter.Default(DefinitionToSchema(stored.schema.input))
                val text = new StringBuilder
                text.append(s"Updated browser script '${stored.name.value}'.\n\n")
                text.append("Current invocation shape:\n")
                text.append(s"  name: ${stored.name.value}\n")
                text.append(s"  arguments matching this schema:\n")
                text.append(schemaJson).append("\n")
                ToolResult.Success(TextToolOutput(text.toString))
              }
            }
          case Some(_) =>
            Task.pure(ToolResult.failure(
              s"Tool '${input.name}' exists but is not a browser script."))
        }
      })
    }.handleError(t =>
      Task.pure(ToolResult.failure(
        s"Failed to update browser script: ${t.getMessage}")))
}
