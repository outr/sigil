package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[ExtractMemoryKeywordsTool]]. The consulted model produces
 * 5–10 retrieval-shaped keywords for a memory's content. The framework
 * persists them on the memory's `keywords` field; the lexical leg of
 * [[sigil.conversation.compression.StandardMemoryRetriever]] picks them
 * up via [[sigil.conversation.ContextMemory.searchText]].
 *
 * Quality bar: keywords must capture the memory's *applicability*, not
 * just its surface vocabulary. A memory "User prefers Scala for backend
 * services" should produce keywords like `scala`, `backend`, `language`,
 * `preference` — terms a future query will plausibly mention. Generic
 * tokens (`task`, `note`, `info`) match nothing usefully.
 */
case class ExtractMemoryKeywordsInput(keywords: List[String]) extends ToolInput derives RW
