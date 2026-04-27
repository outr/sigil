package sigil.script

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[ListScriptToolsTool]] — surface every script-backed
 * tool the caller can see (the union of [[sigil.GlobalSpace]] and
 * [[sigil.Sigil.accessibleSpaces]]).
 *
 * `nameContains` (optional) narrows the listing to tools whose name
 * contains the substring (case-insensitive). Empty input lists
 * everything visible.
 */
case class ListScriptToolsInput(nameContains: Option[String] = None) extends ToolInput derives RW
