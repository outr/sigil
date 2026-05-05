package sigil.tooling.types

import fabric.rw.*
import org.eclipse.lsp4j.Location

/** Sigil-flavored mirror of LSP4J's `Location` — a file URI plus a
  * range. Used by `goto_definition`, `find_references`, etc.
  *
  * `uri` is the wire-form `file://...` LSP returns (LSP servers
  * sometimes URL-encode paths); `filePath` is the decoded local
  * filesystem path for human / agent convenience. */
case class LspLocation(uri: String, filePath: String, range: LspRange) derives RW

object LspLocation {
  def fromLsp4j(loc: Location): LspLocation = {
    val uri  = Option(loc.getUri).getOrElse("")
    val path = scala.util.Try {
      val u = new java.net.URI(uri)
      if (u.getScheme == "file") java.nio.file.Paths.get(u).toString else uri
    }.getOrElse(uri)
    LspLocation(uri = uri, filePath = path, range = LspRange.fromLsp4j(loc.getRange))
  }
}
