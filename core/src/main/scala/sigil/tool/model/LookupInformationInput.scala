package sigil.tool.model

import fabric.rw.*
import lightdb.id.Id
import sigil.information.Information
import sigil.tool.ToolInput

/**
 * Input for the `lookup_information` tool — the agent calls this to
 * resolve an id from the referenced-content catalog to its full
 * [[sigil.information.Information]] record.
 */
case class LookupInformationInput(id: Id[Information]) extends ToolInput derives RW
