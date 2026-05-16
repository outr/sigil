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
case object ExtractMemoriesTool extends TypedTool[ExtractMemoriesInput](
  name = ToolName("extract_memories"),
  description =
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
      |                ALSO use tags to scope a memory to specific operating modes when
      |                the user's directive is explicitly mode-bound. Use the prefix
      |                `mode:NAME` where NAME matches one of the available modes shown in
      |                the system prompt above. Examples:
      |                  - "always create failing tests when coding"  → tags: ["mode:coding"]
      |                  - "in research mode, prefer primary sources" → tags: ["mode:research"]
      |                  - "when coding or writing, never autocomplete" → tags:
      |                    ["mode:coding", "mode:writing"]
      |                Without a `mode:` tag the directive loads in every mode — wasted
      |                prompt budget when it only applies to specific contexts.
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
) {
  override protected def executeTyped(input: ExtractMemoriesInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
