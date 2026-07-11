package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapEvaluateInput(sessionId: String,
                            expression: String,
                            frameId: Option[Int] = None,
                            context: String = "repl")
  extends ToolInput derives RW

/**
 * Evaluate an expression in the debugged program's context. The
 * agent uses this to inspect computed values that aren't directly
 * visible as locals — `someList.size`, `userMap.get("key")`, etc.
 *
 * `context` controls how the adapter formats the result:
 *   - `"repl"` — interactive, full string formatting
 *   - `"watch"` — watch-window style (concise)
 *   - `"hover"` — hover-tooltip style (very concise)
 *   - `"variables"` — pure variable display
 */
final class DapEvaluateTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapEvaluateInput
  type Output = DapEvaluateOutput
  val inputRW = summon[RW[DapEvaluateInput]]
  val outputRW = summon[RW[DapEvaluateOutput]]
  val name = ToolName("dap_evaluate")
  val description =
    """Evaluate an expression in the debugged program's context.
      |
      |`sessionId` selects the active session.
      |`expression` is the source-language code to evaluate (Scala / Python / Go / etc.).
      |`frameId` (optional) — if set, evaluate in that frame's scope; otherwise globally.
      |`context` (default "repl") — "repl" / "watch" / "hover" / "variables" formatting hint.
      |Returns the value (with optional child-reference for structured results).""".stripMargin
  override val examples = List(
    ToolExample(
      "evaluate an expression in a frame",
      DapEvaluateInput(sessionId = "demo-session", expression = "myList.size", frameId = Some(1000))
    )
  )

  override def executeResult(input: DapEvaluateInput, context: ToolContext): Task[ToolResult[DapEvaluateOutput]] =
    withSession(input.sessionId, context) { session =>
      session.evaluate(input.expression, input.frameId, input.context).map { resp =>
        ToolResult.success(DapEvaluateOutput(
          result = resp.getResult,
          `type` = Option(resp.getType),
          variablesReference = resp.getVariablesReference
        ))
      }
    }
}
