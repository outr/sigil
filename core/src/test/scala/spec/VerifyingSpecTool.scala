package spec

import fabric.rw.*
import rapid.Task
import sigil.tool.{ReadOnlyExternalTool, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}

final case class VerifyingSpecInput(scope: String) extends ToolInput derives RW

/**
 * Test-only verification-annotated tool: a stand-in for
 * `bsp_compile` / `lsp_diagnostics`-class checks. A successful settle
 * marks the checkpoint window as verified, resetting the same-target
 * churn chain.
 */
case object VerifyingSpecTool extends Tool with ReadOnlyExternalTool {
  type Input  = VerifyingSpecInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[VerifyingSpecInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("verify_spec_state")
  val description = "Test-only verification tool; checks the named scope."
  override val keywords: Set[String] = Set("verify", "test")
  override def verification: Boolean = true

  override def executeResult(input: VerifyingSpecInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(s"verified ${input.scope}: OK")))
}
