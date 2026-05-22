package sigil.tool.consult

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.provider.{GenerationSettings, ReasoningMode, SummarizationWork, WorkType}
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}

/**
 * Internal-only tool used by [[sigil.conversation.compression.SummaryOnlyCompressor]]
 * to force the consulted model into a structured summary output.
 */
case object SummarizationTool extends Tool with FrameworkConsult {
  type Input  = SummarizationInput
  type Output = TextToolOutput
  val inputRW: RW[SummarizationInput] = summon[RW[SummarizationInput]]
  val outputRW: RW[TextToolOutput]    = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("summarize_conversation")
  val description: String =
    """Emit the final summary of a conversation excerpt as structured output. The supplied
      |`summary` will replace the excerpt in every subsequent turn — so it must stand on its own
      |without the original text.
      |
      |`summary` — the compact narrative. See the system prompt for style / content rules.
      |
      |`tokenEstimate` — your best estimate of `summary` length in tokens (~4 chars/token is fine).
      |The framework uses this to budget future turns.""".stripMargin

  override def paginate: Boolean = false

  /** Condensing work — routes through the cheap summarization tier. */
  override def consultWorkType: WorkType = SummarizationWork

  /** Conservative ceiling. Summarization output scales with the
    * target summary length, so the compressors pass a computed
    * `maxOutputTokens` per call; this value is the fallback cap when
    * no override is supplied. `ReasoningMode.Off` always applies. */
  override def consultSettings: GenerationSettings = GenerationSettings(
    maxOutputTokens = Some(2048),
    reasoningMode = ReasoningMode.Off
  )

  /** Never executed — the framework reads the typed input directly via
    * [[ConsultTool.invoke]]. Resolves to an empty success for completeness. */
  override def executeResult(input: SummarizationInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.success(TextToolOutput("")))
}
