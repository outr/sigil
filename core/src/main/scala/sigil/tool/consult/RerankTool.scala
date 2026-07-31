package sigil.tool.consult

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.provider.{ClassificationWork, GenerationSettings, ReasoningMode, WorkType}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, TextToolOutput, Tool, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Internal tool invoked by [[sigil.vector.LLMReranker]]. Never registered
 * on any agent's roster — the reranker calls it via `ConsultTool.invoke`
 * with `tool_choice = required`.
 */
case object RerankTool extends Tool with FrameworkConsult {
  type Input  = RerankInput
  type Output = TextToolOutput
  val io: ToolIO[RerankInput, TextToolOutput] = ToolIO.derived[RerankInput, TextToolOutput]

  override val name: ToolName = ToolName("rerank_candidates")
  override val description: String =
    """Re-rank a list of candidate snippets by relevance to a query. Return the candidate ids
      |in order from most-relevant to least-relevant.
      |
      |`orderedIds` — a list of candidate id strings. Include every id from the candidate set
      |exactly once. The first id is the most relevant, the last is the least. Ids missing
      |from the output are treated as least-relevant (appended at the end in their original
      |order); ids not in the candidate set are ignored.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(kind = ConsultKind)
  )

  /** Relevance scoring — routes through the cheap classification tier. */
  override def consultWorkType: WorkType = ClassificationWork

  /** Output is a list of candidate id strings — bounded by the
    * candidate-pool size (typically <= 20). 768 tokens covers a full
    * reorder plus the reasoning-spill margin. */
  override def consultSettings: GenerationSettings = GenerationSettings(
    maxOutputTokens = Some(768),
    reasoningMode = ReasoningMode.Off
  )

  /** Never executed — the framework reads the typed input directly via
    * [[ConsultTool.invoke]]. Resolves to an empty success for completeness. */
  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: RerankInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.success(TextToolOutput("")))
}
