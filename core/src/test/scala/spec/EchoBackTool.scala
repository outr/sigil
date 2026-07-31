package spec

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  Resolution,
  TextToolOutput,
  Tool,
  ToolExample,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}
import sigil.tool.ToolContext

/**
 * Test-only tool that echoes its `text` input back as the tool's
 * typed result reading `Echo: <text>`. Used by [[LlamaCppWorkerSpec]] to
 * verify the worker's tool-dispatch path: when the LLM calls
 * `echo_back` with a known text, the spec inspects the worker's
 * settle payload and prior reasoning to confirm the tool ran and the
 * result was folded into the next iteration.
 */
case object EchoBackTool extends Tool {
  type Input = EchoBackInput
  type Output = TextToolOutput
  val io: ToolIO[EchoBackInput, TextToolOutput] = ToolIO.derived[EchoBackInput, TextToolOutput].withExamples(
    ToolExample("Echo a marker string", EchoBackInput("hello-from-tool"))
  )
  override val name = ToolName("echo_back")
  override val description =
    """Echoes the supplied text back. Test-only tool: call with `text`
      |to verify a tool-call round-trip works.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Pure)),
    discovery = DiscoverySpec(keywords = Set("echo", "test"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: EchoBackInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(s"Echo: ${input.text}")))
}
