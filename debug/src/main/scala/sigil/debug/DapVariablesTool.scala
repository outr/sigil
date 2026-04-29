package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapVariablesInput(sessionId: String,
                             variablesReference: Int,
                             maxResults: Int = 100) extends ToolInput derives RW

/**
 * Fetch variables from a scope or expanded structured value.
 * `variablesReference` can be from `dap_scopes` (top-level scope),
 * from a previous `dap_variables` (expanded sub-tree of a structured
 * value), or from `dap_evaluate` (when the result has children).
 *
 * Capped at `maxResults` so a giant collection doesn't blow the
 * agent's context.
 */
final class DapVariablesTool(val manager: DapManager) extends TypedTool[DapVariablesInput](
  name = ToolName("dap_variables"),
  description =
    """Fetch variables for a scope or structured value's children.
      |
      |`sessionId` selects the active session.
      |`variablesReference` is from a prior `dap_scopes` / `dap_variables` / `dap_evaluate` call.
      |`maxResults` (default 100) caps the response.
      |Each variable shows name, value, type, and a child-reference (if expandable).""".stripMargin,
  examples = List(
    ToolExample(
      "fetch locals for a scope",
      DapVariablesInput(sessionId = "demo-session", variablesReference = 1001)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapVariablesInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.variables(input.variablesReference).map { vars =>
        if (vars.isEmpty) "No variables."
        else {
          val capped = vars.take(input.maxResults)
          val rendered = capped.map { v =>
            val tpe = Option(v.getType).map(t => s": $t").getOrElse("")
            val childRef = if (v.getVariablesReference != 0) s"  [ref=${v.getVariablesReference}]" else ""
            s"  ${v.getName}$tpe = ${v.getValue}$childRef"
          }.mkString("\n")
          if (vars.size > input.maxResults)
            s"$rendered\n... (${vars.size - input.maxResults} more)"
          else rendered
        }
      }
    }
}
