package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[ExtractMemoriesTool]]. The consulted model lists durable
 * facts it observed in a conversation excerpt — one fact per `facts`
 * entry. The compressor converts each into a
 * [[sigil.conversation.ContextMemory]] in the app-chosen space.
 *
 * Keep facts self-contained: a reader encountering the fact alone
 * (without the transcript) should still be able to act on it. "She
 * prefers dark mode" is useless; "User `alice` prefers dark mode" is
 * a fact.
 */
case class ExtractMemoriesInput(facts: List[String]) extends ToolInput derives RW
