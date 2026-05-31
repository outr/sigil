package sigil.tool.output

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.tool.ToolInput

/**
 * Input for `summarize_output` — the on-demand, LLM-derived summary rung
 * of the bulk-output ladder. `reference` is a prior bulk tool's `callId`
 * (or any node id from its result). `focus` optionally steers the summary
 * ("which files dominate", "categorize these matches"); absent, it's a
 * neutral overview. `conversationId` reads a reference from a
 * parent/worker conversation (default: the current one).
 */
case class SummarizeOutputInput(reference: String,
                                focus: Option[String] = None,
                                conversationId: Option[Id[Conversation]] = None) extends ToolInput derives RW
