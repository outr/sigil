package sigil.tooling.types

import fabric.rw.*

/**
 * Sigil-flavored mirror of LSP4J's `Position`. 1-based line +
 * character so the agent's typed view matches what users see in
 * IDEs / editors. The JSON shape on the wire is what consuming
 * downstream code pattern-matches against; this case class is the
 * typed convenience.
 *
 * Constructed from `lsp4j.Position` via [[fromLsp4j]] — converts
 * LSP4J's 0-based indices to 1-based for human readability.
 */
case class LspPosition(line: Int, column: Int) derives RW

object LspPosition {
  def fromLsp4j(p: org.eclipse.lsp4j.Position): LspPosition =
    LspPosition(line = p.getLine + 1, column = p.getCharacter + 1)
}
