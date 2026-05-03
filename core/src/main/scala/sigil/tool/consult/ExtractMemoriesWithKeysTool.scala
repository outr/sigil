package sigil.tool.consult

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedTool}

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
case object ExtractMemoriesWithKeysTool extends TypedTool[ExtractMemoriesWithKeysInput](
  name = ToolName("extract_memories_with_keys"),
  description =
    """Extract durable facts from a conversation excerpt. Each fact must be self-contained
      |(a reader seeing the fact alone must still be able to act on it).
      |
      |For each fact, return:
      |  - `content` — the full fact text, self-contained (quote identifiers by name).
      |  - `label`   — short human-readable label (e.g. "Preferred language").
      |  - `key`     — OPTIONAL stable, dot-separated identifier (e.g. "user.preferred_language",
      |                 "project.deploy_target"). Supply when the fact represents a durable
      |                 identity slot whose value may change over time — same key across
      |                 conversations enables versioning. Omit for one-shot facts.
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
) {
  override protected def executeTyped(input: ExtractMemoriesWithKeysInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
