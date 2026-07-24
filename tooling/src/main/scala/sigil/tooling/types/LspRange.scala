package sigil.tooling.types

import fabric.rw.*

/**
 * Sigil-flavored mirror of LSP4J's `Range` — start + end positions
 * defining a span in a text document. Both ends are 1-based via
 * [[LspPosition.fromLsp4j]].
 */
case class LspRange(start: LspPosition, end: LspPosition) derives RW

object LspRange {
  def fromLsp4j(r: org.eclipse.lsp4j.Range): LspRange =
    LspRange(start = LspPosition.fromLsp4j(r.getStart), end = LspPosition.fromLsp4j(r.getEnd))
}
