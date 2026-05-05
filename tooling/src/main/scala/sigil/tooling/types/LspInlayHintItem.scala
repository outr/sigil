package sigil.tooling.types

import fabric.rw.*

/** Inlay hint at a position. `kind` is "type", "param", or "hint"
  * (the latter for unkinded hints — server didn't categorize). */
case class LspInlayHintItem(kind: String, position: LspPosition, label: String) derives RW
