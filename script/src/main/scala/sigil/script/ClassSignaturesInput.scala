package sigil.script

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[ClassSignaturesTool]]. The agent supplies a
 * fully-qualified class name (e.g. `spice.http.client.HttpClient`)
 * and the tool returns its constructor signatures, public methods
 * (with parameter and return types), and public fields.
 *
 * Use this AFTER `library_lookup` resolves an unqualified name to
 * one or more FQNs; this is the deeper inspection that gives the
 * agent enough information to write a correct call site.
 */
case class ClassSignaturesInput(
  @description("Fully-qualified class name to introspect. E.g. `spice.http.client.HttpClient`, `fabric.io.JsonParser$`. The trailing `$` for Scala objects is optional — the tool resolves both shapes.")
  fqn: String
) extends ToolInput derives RW
