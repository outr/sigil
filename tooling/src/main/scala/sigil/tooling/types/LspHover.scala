package sigil.tooling.types

import fabric.rw.*
import org.eclipse.lsp4j.Hover

import scala.jdk.CollectionConverters.*

/** Sigil-flavored mirror of LSP4J's `Hover`. The agent typically
  * cares about `contents` (the markdown / plain-text body the IDE
  * tooltip renders) and optionally `range` (the source span the
  * hover applies to).
  *
  * LSP4J's hover contents is a union (string | MarkedString |
  * MarkupContent | List of any of the above). We flatten everything
  * to `contents: String` (rendered as markdown) for agent
  * consumption — apps that need to distinguish markdown from plain
  * text can inspect `kind`. */
case class LspHover(contents: String,
                    kind: String = "markdown",
                    range: Option[LspRange] = None) derives RW

object LspHover {
  def fromLsp4j(h: Hover): LspHover = {
    val rangeOpt = Option(h.getRange).map(LspRange.fromLsp4j)
    val contentsValue = h.getContents
    val (rendered, kind) =
      if (contentsValue == null) ("", "markdown")
      else if (contentsValue.isLeft) {
        // List<Either<String, MarkedString>>
        val parts = contentsValue.getLeft.asScala.toList.map { either =>
          if (either.isLeft) either.getLeft
          else {
            val ms = either.getRight
            val lang = Option(ms.getLanguage).filter(_.nonEmpty)
            val v = Option(ms.getValue).getOrElse("")
            lang.map(l => s"```$l\n$v\n```").getOrElse(v)
          }
        }
        (parts.mkString("\n\n"), "markdown")
      } else if (contentsValue.isRight) {
        val mc = contentsValue.getRight
        (Option(mc.getValue).getOrElse(""), Option(mc.getKind).getOrElse("markdown"))
      } else ("", "markdown")
    LspHover(contents = rendered, kind = kind, range = rangeOpt)
  }
}
