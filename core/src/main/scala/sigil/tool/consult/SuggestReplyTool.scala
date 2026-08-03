package sigil.tool.consult

import rapid.Task
import sigil.provider.{GenerationSettings, OutputTokenCap, ReasoningMode, SummarizationWork, WorkType}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, TextToolOutput, Tool, ToolContext, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Internal-only consult that predicts what the USER will say next.
 * The framework fires it on a background fiber after a turn settles
 * with a user-visible reply and delivers the result as a transient
 * [[sigil.signal.SuggestedReplies]] Notice.
 *
 * The tool's typed input IS the payload — the framework reads it
 * directly through [[ConsultTool.invokeRouted]]; there is no
 * `executeResult` body worth running.
 */
case object SuggestReplyTool extends Tool with FrameworkConsult {
  type Input  = SuggestReplyInput
  type Output = TextToolOutput
  val io: ToolIO[SuggestReplyInput, TextToolOutput] = ToolIO.derived[SuggestReplyInput, TextToolOutput]

  override val name: ToolName = ToolName("suggest_reply")
  override val description: String =
    """Predict the message the user is most likely to send next.
      |
      |Write each suggestion in the USER's voice — first person, as if they typed it. Plain
      |prose only: no quotation marks, no markdown, no bullet prefixes, no trailing commentary.
      |Keep each one to a single short sentence or fragment a person would actually type.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(kind = ConsultKind)
  )

  /** A short list of short strings — the cheap summarization tier
    * handles it. */
  override def consultWorkType: WorkType = SummarizationWork

  /** Output is at most a handful of one-line strings; 192 tokens
    * covers the structured payload plus the reasoning-spill margin. */
  override def consultSettings: GenerationSettings = GenerationSettings(
    outputTokenCap = OutputTokenCap.Below(192),
    reasoningMode  = ReasoningMode.Off
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: SuggestReplyInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.success(TextToolOutput("")))
}
