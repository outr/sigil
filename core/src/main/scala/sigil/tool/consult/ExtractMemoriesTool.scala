package sigil.tool.consult

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.provider.{GenerationSettings, OutputTokenCap, ReasoningMode, SummarizationWork, WorkType}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, TextToolOutput, Tool, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Internal tool invoked by both
 * [[sigil.conversation.compression.extract.StandardMemoryExtractor]]
 * (per-turn) and
 * [[sigil.conversation.compression.MemoryContextCompressor]]
 * (compression-time). Never registered on any agent's roster — the
 * extractor calls it via `ConsultTool.invoke` with
 * `tool_choice = required`.
 *
 * Each fact may carry a stable `key` (e.g. "user.preferred_language")
 * so `upsertMemoryByKey` can version the record instead of creating a
 * new row every time the same fact shows up. Omit the key for
 * one-shot facts that don't represent a durable identity slot.
 */
case object ExtractMemoriesTool extends Tool with FrameworkConsult {
  type Input = ExtractMemoriesInput
  type Output = TextToolOutput
  val io: ToolIO[ExtractMemoriesInput, TextToolOutput] = ToolIO.derived[ExtractMemoriesInput, TextToolOutput]

  override val name: ToolName = ToolName("extract_memories")
  override val description: String =
    """Extract durable facts from a conversation excerpt. Each fact must be self-contained
      |(a reader seeing the fact alone must still be able to act on it).
      |
      |For each fact, return:
      |  - `content` — the full fact text, self-contained (quote identifiers by name).
      |  - `label`   — short human-readable label (e.g. "Preferred language").
      |  - `key`     — stable, dot-separated identifier (e.g. "user.preferred_language",
      |                "project.deploy_target", "user.name", "user.location"). ALWAYS supply
      |                a key for facts that represent a durable identity slot whose value
      |                may change over time — name, location, preference, theme, language,
      |                deploy target, contact info, decisions, commitments. The same key
      |                across conversations enables versioning. Only omit for one-shot
      |                facts that genuinely have no identity-slot semantics (rare).
      |  - `tags`    — optional categorization tokens (e.g. ["preference", "language"]).
      |                For mode-scoped directives ("always do X when coding"), prefix the
      |                applicable mode name(s) with `mode:` — e.g. `tags: ["mode:coding"]`.
      |
      |Include only facts that future agents will genuinely need:
      |  - identifiers, names, numbers, URLs explicitly stated → KEY REQUIRED
      |  - preferences, decisions, and commitments → KEY REQUIRED
      |  - constraints and requirements ("must be X", "cannot exceed Y") → KEY REQUIRED
      |
      |Examples of well-formed keys:
      |  - user.name, user.location, user.role
      |  - user.preferred_language, user.preferred_theme, user.preferred_editor
      |  - project.deploy_target, project.repo_url
      |  - contact.email, contact.slack_handle
      |
      |Do NOT include:
      |  - intermediate reasoning, small-talk, acknowledgements
      |  - content that belongs in a summary (narrative / ongoing context).""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(kind = ConsultKind)
  )

  /**
   * Fact extraction is condensing work — routes through the cheap
   * summarization tier.
   */
  override def consultWorkType: WorkType = SummarizationWork

  /**
   * Output is a list of structured facts. The cap must accommodate
   * rich excerpts (KB-scale pastes, agent replies enumerating many
   * facts) — the model buffers the full structured `tool_use` input
   * on the wire before any `partial_json` delta streams, so a tight
   * cap cuts the buffered payload mid-emission and produces an empty
   * tool_use with zero recorded memories. 8192 covers the worst-case
   * shape while staying well under any current frontier model's
   * 64K output ceiling. Per-call site tuning lives on the caller
   * (e.g. [[sigil.conversation.compression.extract.StandardMemoryExtractor.maxExtractionTokens]]).
   */
  override def consultSettings: GenerationSettings = GenerationSettings(
    outputTokenCap = OutputTokenCap.Below(8192),
    reasoningMode = ReasoningMode.Off
  )

  /**
   * Never executed — the framework reads the typed input directly via
   * [[ConsultTool.invoke]]. Resolves to an empty success for completeness.
   */
  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ExtractMemoriesInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.success(TextToolOutput("")))
}
