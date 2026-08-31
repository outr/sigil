package sigil.tooling.types

import fabric.rw.*

/**
 * Single source entry for a BSP target. `kind` is "dir" or "file"
 * matching bsp4j's `SourceItemKind`.
 */
case class BspSourceItem(uri: String, kind: String, generated: Boolean) derives RW

object BspSourceItem {
  def fromBsp4j(s: ch.epfl.scala.bsp4j.SourceItem): BspSourceItem = {
    val kind = if (s.getKind == ch.epfl.scala.bsp4j.SourceItemKind.DIRECTORY) "dir" else "file"
    BspSourceItem(uri = s.getUri, kind = kind, generated = Option(s.getGenerated).exists(_.booleanValue))
  }
}
