package sigil.tool.consult

import rapid.Task
import sigil.provider.{GenerationSettings, OutputTokenCap, ReasoningMode, SummarizationWork, WorkType}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, TextToolOutput, Tool, ToolContext, ToolIO, ToolName, ToolProfile,
  ToolResult, ToolSpec}

/**
 * Internal-only one-shot tool. Invoked by
 * [[sigil.maintenance.MemoryConsolidationTask]] for each
 * near-duplicate memory cluster the sweep finds — decides whether the
 * cluster states one fact (merge) or genuinely distinct facts that
 * merely embed near each other (keep separate).
 *
 * Never registered on any agent's roster — the sweep calls it via
 * [[ConsultTool.invokeRouted]] with `tool_choice = required`.
 */
case object ConsolidateMemoriesTool extends Tool with FrameworkConsult {
  type Input  = ConsolidateMemoriesInput
  type Output = TextToolOutput
  val io: ToolIO[ConsolidateMemoriesInput, TextToolOutput] = ToolIO.derived[ConsolidateMemoriesInput, TextToolOutput]

  override val name: ToolName = ToolName("consolidate_memories")
  override val description: String =
    """Decide whether a cluster of near-duplicate memories should be merged into one record.
      |
      |You will be shown numbered memories that embedded very close to each other. Return:
      |
      |  - `verdict` — `"Merge"` when every member states the SAME underlying fact (possibly with
      |    different wording or partial detail). `"KeepSeparate"` when the members are distinct
      |    facts — different subjects, different values, contradictory claims, or facts that would
      |    lose meaning if collapsed. When unsure, prefer `"KeepSeparate"` — a duplicate costs a
      |    few tokens; a wrong merge loses information.
      |
      |  - `mergedFact` — required for `"Merge"`: ONE self-contained statement that preserves every
      |    non-redundant detail across the members (identifiers, numbers, URLs verbatim). A reader
      |    seeing only the merged fact must lose nothing the members carried.
      |
      |  - `mergedLabel` — short human-readable label for the merged record.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(kind = ConsultKind)
  )

  /** Condensing work — routes through the cheap summarization tier. */
  override def consultWorkType: WorkType = SummarizationWork

  /** Output is one verdict + one merged fact/label. 512 covers a rich
    * merged fact plus the reasoning-spill margin. */
  override def consultSettings: GenerationSettings = GenerationSettings(
    outputTokenCap = OutputTokenCap.Below(512),
    reasoningMode  = ReasoningMode.Off
  )

  /** Never executed — the framework reads the typed input directly via
    * [[ConsultTool.invokeRouted]]. Resolves to an empty success for
    * completeness. */
  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ConsolidateMemoriesInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.success(TextToolOutput("")))
}
