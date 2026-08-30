package bench

import rapid.Task
import sigil.provider.{AnalysisWork, GenerationSettings, OutputTokenCap, ReasoningMode, WorkType}
import sigil.tool.consult.{ConsultKind, FrameworkConsult}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, TextToolOutput, Tool, ToolContext, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/** The consult tool [[BenchJudge]] forces — never rostered on an
  * agent; the framework reads the typed input directly. */
case object JudgeVerdictTool extends Tool with FrameworkConsult {
  type Input = JudgeVerdictInput
  type Output = TextToolOutput
  val io: ToolIO[JudgeVerdictInput, TextToolOutput] = ToolIO.derived[JudgeVerdictInput, TextToolOutput]

  override val name: ToolName = ToolName("judge_verdict")
  override val description: String =
    """Deliver your correctness verdict on a candidate response.
      |
      |`correct` is true when the response conveys the gold answer's substance — extra detail,
      |different wording, and a conversational frame are all fine. It is false when the
      |response contradicts the gold answer, omits the asked-for fact, or declines to answer.
      |`reasoning` is ONE short sentence naming what decided it.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(kind = ConsultKind)
  )

  override def consultWorkType: WorkType = AnalysisWork

  override def consultSettings: GenerationSettings = GenerationSettings(
    outputTokenCap = OutputTokenCap.Below(512),
    reasoningMode = ReasoningMode.Off
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit { (_, _: ToolContext) =>
    Task.pure(ToolResult.success(TextToolOutput("")))
  }
}
