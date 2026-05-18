package sigil.tooling.types

import fabric.rw.*

/**
 * Flat document-symbol record. `depth` encodes the nesting level
 * inside the file's symbol tree — `0` for top-level, `1` for a
 * member of a top-level container, etc. Flat representation avoids
 * recursive case-class derivation.
 */
case class LspDocumentSymbolEntry(kind: String,
                                  name: String,
                                  position: LspPosition,
                                  depth: Int)
  derives RW
