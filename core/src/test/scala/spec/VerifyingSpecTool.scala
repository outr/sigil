package spec

import fabric.rw.*
import rapid.Task
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  Resolution,
  TextToolOutput,
  Tool,
  ToolContext,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

final case class VerifyingSpecInput(scope: String) extends ToolInput derives RW

/**
 * Test-only verification-annotated tool: a stand-in for
 * `bsp_compile` / `lsp_diagnostics`-class checks. A successful settle
 * marks the checkpoint window as verified, resetting the same-target
 * churn chain.
 */
case object VerifyingSpecTool extends Tool {
  type Input = VerifyingSpecInput
  type Output = TextToolOutput
  val io: ToolIO[VerifyingSpecInput, TextToolOutput] = ToolIO.derived[VerifyingSpecInput, TextToolOutput]
  override val name = ToolName("verify_spec_state")
  override val description = "Test-only verification tool; checks the named scope."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("verify", "test"))
  )
  override def verification: Boolean = true

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: VerifyingSpecInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(s"verified ${input.scope}: OK")))
}
