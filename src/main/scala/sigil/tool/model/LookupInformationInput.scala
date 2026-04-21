package sigil.tool.model

import fabric.rw.*
import lightdb.id.Id
import sigil.information.Information
import sigil.tool.ToolInput

/**
 * Input for the `lookup_information` tool — the agent calls this to
 * resolve a referenced [[sigil.information.Information]] id to its full
 * content.
 */
case class LookupInformationInput(id: Id[Information]) extends ToolInput derives RW
