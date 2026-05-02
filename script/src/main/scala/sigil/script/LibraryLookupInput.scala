package sigil.script

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[LibraryLookupTool]]. The agent supplies an unqualified
 * symbol or method-style reference (`HttpClient`, `JsonParser`,
 * `Task.map`, `URL.parse`); the tool fuzzy-matches against the
 * executor's classpath and returns one or more fully-qualified
 * candidates with a brief signature summary.
 */
case class LibraryLookupInput(
  @description("An unqualified class name, method reference (e.g. `Task.map`), or other symbol the agent is unsure how to use. Case-insensitive; fuzzy-matched against the executor's classpath. Examples: `HttpClient`, `JsonParser`, `Task.map`.")
  symbol: String
) extends ToolInput derives RW
