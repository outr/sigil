package sigil.tooling.types

import fabric.rw.*

/** Build output entry. `kind` is "dir" or "file" matching bsp4j's
  * `OutputPathItemKind`. */
case class BspOutputPathItem(uri: String, kind: String) derives RW

object BspOutputPathItem {
  def fromBsp4j(p: ch.epfl.scala.bsp4j.OutputPathItem): BspOutputPathItem = {
    val kind = if (p.getKind == ch.epfl.scala.bsp4j.OutputPathItemKind.DIRECTORY) "dir" else "file"
    BspOutputPathItem(uri = p.getUri, kind = kind)
  }
}
