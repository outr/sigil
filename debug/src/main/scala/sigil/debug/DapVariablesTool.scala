package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  Resolution,
  Tool,
  ToolExample,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

case class DapVariablesInput(sessionId: String, variablesReference: Int, maxResults: Int = 100) extends ToolInput derives RW

/**
 * Fetch variables from a scope or expanded structured value.
 * `variablesReference` can be from `dap_scopes` (top-level scope),
 * from a previous `dap_variables` (expanded sub-tree of a structured
 * value), or from `dap_evaluate` (when the result has children).
 *
 * Capped at `maxResults` so a giant collection doesn't blow the
 * agent's context.
 */
final class DapVariablesTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapVariablesInput
  type Output = DapVariablesOutput
  val io: ToolIO[DapVariablesInput, DapVariablesOutput] = ToolIO.derived[DapVariablesInput, DapVariablesOutput].withExamples(
    ToolExample(
      "fetch locals for a scope",
      DapVariablesInput(sessionId = "demo-session", variablesReference = 1001)
    )
  )
  override val name = ToolName("dap_variables")
  override val description =
    """Fetch variables for a scope or structured value's children.
      |
      |`sessionId` selects the active session.
      |`variablesReference` is from a prior `dap_scopes` / `dap_variables` / `dap_evaluate` call.
      |`maxResults` (default 100) caps the response.
      |Each variable shows name, value, type, and a child-reference (if expandable).""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "variables", "locals", "inspect", "values"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DapVariablesInput, context: ToolContext): Task[ToolResult[DapVariablesOutput]] =
    withSession(input.sessionId, context) { session =>
      session.variables(input.variablesReference).map { vars =>
        val capped = vars.take(input.maxResults)
        val rendered = capped.map { v =>
          DapVariableInfo(
            name = v.getName,
            value = v.getValue,
            `type` = Option(v.getType),
            variablesReference = v.getVariablesReference
          )
        }
        ToolResult.success(DapVariablesOutput(
          variables = rendered,
          truncated = math.max(0, vars.size - input.maxResults)
        ))
      }
    }
}
