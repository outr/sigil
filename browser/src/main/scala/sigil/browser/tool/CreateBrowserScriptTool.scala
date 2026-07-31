package sigil.browser.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.browser.WebBrowserMode
import sigil.browser.{BrowserScript, BrowserSigil, CookieJar}
import sigil.tool.{
  DefinitionToSchema,
  DiscoverySpec,
  Effect,
  JsonSchemaToDefinition,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

/**
 * Persist a new [[BrowserScript]] under the framework's policy-
 * resolved [[sigil.SpaceId]]. The agent supplies the script's
 * surface and step list; this tool resolves the space via
 * [[BrowserSigil.browserScriptSpace]], builds the record, and writes
 * it to `SigilDB.tools` so future turns can `find_capability` and
 * invoke it like any other tool.
 *
 * The result text confirms the persisted script and carries its
 * invocation schema so the agent can call it back without an extra
 * `find_capability` round-trip.
 */
case object CreateBrowserScriptTool extends Tool {
  type Input = CreateBrowserScriptInput
  type Output = TextToolOutput
  // `steps` is a deliberately polymorphic action catalog (each step
  // variant mirrors a primitive `browser_*` tool's args), so the
  // schema is kept via the checked `withSchema` decision.
  val io: ToolIO[CreateBrowserScriptInput, TextToolOutput] = ToolIO.withSchema[CreateBrowserScriptInput, TextToolOutput](
    summon[fabric.rw.RW[CreateBrowserScriptInput]].definition
  )

  override val name = ToolName("create_browser_script")
  override val description =
    """Persist a new browser-script tool the agent (or another agent in scope) can later invoke
      |through `find_capability`. The script's `steps` run against the per-conversation browser
      |controller — same surface as the primitive `browser_*` tools — so any sequence the agent can
      |demonstrate manually can be saved as a replayable script.
      |
      |`name` must be unique. `parameters` is a JSON Schema; leave empty to accept no args.
      |`steps` is a list of typed action records; string fields support `${arg.path}` and
      |`${outputs.<name>}` placeholders. `cookieJarId` references a previously-saved CookieJar so
      |replays restore logged-in state. `space` is an optional string hint asking the framework to
      |pin the tool under a specific space — the active Sigil's `browserScriptSpace` policy
      |decides whether to honor it.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(
      keywords = Set("create", "browser", "script", "automate", "record", "save", "replay"),
      modes = Set(WebBrowserMode.id)
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: CreateBrowserScriptInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = ctx.sigil match {
    case bs: BrowserSigil =>
      ToolName.parse(input.name) match {
        case Left(reason) =>
          Task.pure(ToolResult.failure(
            message = s"Invalid browser script name '${input.name}': $reason",
            hint = Some(s"Pick a name matching ${ToolName.DynamicGrammar} and retry.")
          ))
        case Right(toolName) =>
          bs.browserScriptSpace(ctx.chain, input.space).flatMap { resolvedSpace =>
            val script = BrowserScript(
              name = toolName,
              description = input.description,
              parameters = JsonSchemaToDefinition(input.parameters),
              steps = input.steps,
              space = resolvedSpace,
              cookieJarId = input.cookieJarId.map(s => Id[CookieJar](s)),
              keywords = input.keywords,
              createdBy = Some(ctx.caller)
            )
            ctx.sigil.createTool(script).map { stored =>
              val schemaJson = JsonFormatter.Default(DefinitionToSchema(stored.schema.input))
              val text = new StringBuilder
              text.append(s"Persisted browser script '${stored.name.value}' under space '${resolvedSpace.value}' ")
              text.append(s"(${input.steps.size} steps).\n\n")
              text.append("To invoke on a subsequent turn, emit a tool_call with:\n")
              text.append(s"  name: ${stored.name.value}\n")
              text.append(s"  arguments matching this schema:\n")
              text.append(schemaJson).append("\n\n")
              text.append("Authoring follow-ups: update_browser_script, delete_browser_script.\n")
              ToolResult.Success(TextToolOutput(text.toString))
            }
          }.handleError(t =>
            Task.pure(ToolResult.failure(
              s"Failed to create browser script: ${t.getMessage}")))
      }
    case _ =>
      Task.pure(ToolResult.failure(
        "Sigil instance does not mix in BrowserSigil; cannot create browser scripts."))
  }
}
