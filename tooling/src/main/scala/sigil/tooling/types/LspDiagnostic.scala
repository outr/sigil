package sigil.tooling.types

import fabric.rw.*
import org.eclipse.lsp4j.Diagnostic

/**
 * Sigil-flavored mirror of LSP4J's `Diagnostic`. Lets agents
 * pattern-match on `severity == LspSeverity.Error` and iterate
 * `range` directly instead of regex-parsing rendered strings.
 *
 * `code` and `source` are optional because LSP servers vary in
 * what they populate. The original LSP code can be string-or-int;
 * we render whichever side is set as a string for uniformity.
 */
case class LspDiagnostic(filePath: String,
                         range: LspRange,
                         severity: LspSeverity,
                         message: String,
                         code: Option[String] = None,
                         source: Option[String] = None)
  derives RW

object LspDiagnostic {
  def fromLsp4j(filePath: String, d: Diagnostic): LspDiagnostic = {
    // LSP4J's Diagnostic.code is Either[String, Integer]; flatten
    // either side to a String so consumers don't need union-handling.
    val codeStr: Option[String] = Option(d.getCode).flatMap { c =>
      if (c.isLeft) Option(c.getLeft) else if (c.isRight) Option(c.getRight).map(_.toString) else None
    }
    // LSP4J newer versions surface `message` as
    // `Either[String, MarkupContent]` to support markdown bodies.
    // Flatten to plain text for the agent's typed view: left side is
    // the string verbatim; right side's MarkupContent has a `getValue`.
    val msg: String = d.getMessage match {
      case null => ""
      case e if e.isLeft => Option(e.getLeft).getOrElse("")
      case e if e.isRight => Option(e.getRight).flatMap(mc => Option(mc.getValue)).getOrElse("")
      case _ => ""
    }
    LspDiagnostic(
      filePath = filePath,
      range = LspRange.fromLsp4j(d.getRange),
      severity = LspSeverity.fromLsp4j(d.getSeverity),
      message = msg,
      code = codeStr,
      source = Option(d.getSource)
    )
  }
}
