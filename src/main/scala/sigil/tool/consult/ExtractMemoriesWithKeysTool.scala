package sigil.tool.consult

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{Tool, ToolExample}

/**
 * Internal tool invoked by
 * [[sigil.conversation.compression.extract.StandardMemoryExtractor]].
 * Never registered on any agent's roster — the extractor calls it via
 * `ConsultTool.invoke` with `tool_choice = required`.
 *
 * Richer than [[ExtractMemoriesTool]]: each fact carries a stable
 * `key` (e.g. "user.preferred_language") so `upsertMemoryByKey` can
 * version the record instead of creating a new row every time the
 * same fact shows up.
 */
object ExtractMemoriesWithKeysTool extends Tool[ExtractMemoriesWithKeysInput] {
  override protected def uniqueName: String = "extract_memories_with_keys"

  override protected def description: String =
    """Extract durable facts from a conversation excerpt. Each fact must be self-contained
      |(a reader seeing the fact alone must still be able to act on it).
      |
      |For each fact, return:
      |  - `key`     — a stable, dot-separated identifier (e.g. "user.preferred_language",
      |                 "project.deploy_target", "user.billing.plan"). Same fact across
      |                 conversations must use the same key so versioning works.
      |  - `label`   — short human-readable label (e.g. "Preferred language").
      |  - `content` — the full fact text, self-contained (quote identifiers by name).
      |  - `tags`    — optional categorization tokens (e.g. ["preference", "language"]).
      |
      |Include only facts that future agents will genuinely need:
      |  - identifiers, names, numbers, URLs explicitly stated
      |  - preferences, decisions, and commitments
      |  - constraints and requirements ("must be X", "cannot exceed Y")
      |
      |Do NOT include:
      |  - intermediate reasoning, small-talk, acknowledgements
      |  - content that belongs in a summary (narrative / ongoing context).""".stripMargin

  override protected def examples: List[ToolExample[ExtractMemoriesWithKeysInput]] = Nil

  override def execute(input: ExtractMemoriesWithKeysInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
