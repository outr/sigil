package sigil.tool.core

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[FindCapabilityTool]]. The `query` is a free-form search string —
 * the LLM phrases it however it wants ("send a slack message", "count items in
 * the database", "generate an invoice"). The [[ToolManager]] decides how to
 * match against the app's tool catalog.
 */
case class FindCapabilityInput(query: String) extends ToolInput derives RW
