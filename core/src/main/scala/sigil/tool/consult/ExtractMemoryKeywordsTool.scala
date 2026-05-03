package sigil.tool.consult

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedTool}

/**
 * Internal-only one-shot tool. Invoked by [[sigil.Sigil.persistMemory]]
 * / [[sigil.Sigil.upsertMemoryByKey]] right before the write to populate
 * [[sigil.conversation.ContextMemory.keywords]] from the memory's
 * content.
 *
 * Never registered on any agent's roster — the framework calls it via
 * [[ConsultTool.invoke]] with `tool_choice = required`.
 */
case object ExtractMemoryKeywordsTool extends TypedTool[ExtractMemoryKeywordsInput](
  name = ToolName("extract_memory_keywords"),
  description =
    """List 5–10 retrieval keywords for the memory shown. The framework persists them
      |alongside the memory and uses them on future turns to surface the memory when an
      |agent's stated focus matches.
      |
      |Pick keywords a future query will plausibly mention:
      |  - identifiers, names, languages, frameworks, file types, components, concepts
      |  - applicability terms (when the memory's rule / preference applies — e.g. "code-style",
      |    "naming", "review")
      |  - distinguishing tokens that disambiguate from unrelated memories
      |
      |Do NOT include:
      |  - generic helpers ("task", "note", "info", "data")
      |  - stop words or articles
      |  - the literal words "user" / "agent" / "memory" / "rule" (every memory has these;
      |    they don't help retrieval)
      |
      |Lowercase, single-word tokens preferred. Aim for 5–10 keywords; precision over volume.""".stripMargin
) {
  override protected def executeTyped(input: ExtractMemoryKeywordsInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
