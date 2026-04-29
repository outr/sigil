package sigil.tool.provider

import fabric.rw.*
import sigil.tool.ToolInput

/** Args for [[ListProviderStrategiesTool]] — no fields. Returns
  * every saved strategy visible to the caller in the conversation's
  * space, plus a marker for the currently-assigned record. */
case class ListProviderStrategiesInput() extends ToolInput derives RW
