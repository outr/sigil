package sigil.tool.consult

import rapid.Task
import sigil.provider.{GenerationSettings, OutputTokenCap, ReasoningMode, SummarizationWork, WorkType}
import sigil.tool.{
  DiscoverySpec, Effect, Freshness, Resolution, TextToolOutput, Tool, ToolContext, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec
}

/**
 * Internal consult invoked by
 * [[sigil.conversation.compression.ConsultMemoryDistiller]] at memory
 * ingest. Never registered on any agent's roster — called via
 * `ConsultTool.invoke` with `tool_choice = required`. The typed input
 * IS the distillation payload.
 */
case object DistillMemoryTool extends Tool with FrameworkConsult {
  type Input = DistillMemoryInput
  type Output = TextToolOutput
  val io: ToolIO[DistillMemoryInput, TextToolOutput] = ToolIO.derived[DistillMemoryInput, TextToolOutput]

  override val name: ToolName = ToolName("distill_memory")
  override val description: String =
    """Distill a stored memory for per-turn injection and retrieval.
      |
      |`summary` (required): ONE line capturing the fact's core — what a reader scanning a
      |list of memories must see to know this one exists and what it holds. Plain statement,
      |no preamble, no markdown.
      |
      |`retrievalText` (optional): a self-contained rewrite of the fact optimized for
      |retrieval — name every entity explicitly, resolve pronouns, state relationships
      |directly, keep concrete identifiers verbatim. A question whose answer lives in this
      |fact should share vocabulary with the rewrite. Omit when the fact is already short
      |and self-contained.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(kind = ConsultKind)
  )

  override def consultWorkType: WorkType = SummarizationWork

  /**
   * A one-line summary plus an optional passage-sized rewrite.
   */
  override def consultSettings: GenerationSettings = GenerationSettings(
    outputTokenCap = OutputTokenCap.Below(1024),
    reasoningMode = ReasoningMode.Off
  )

  /**
   * Never executed — the framework reads the typed input directly via
   * [[ConsultTool.invoke]].
   */
  protected def resolve: Resolution[Input, Output] = Resolution.Explicit { (_, _: ToolContext) =>
    Task.pure(ToolResult.success(TextToolOutput("")))
  }
}
