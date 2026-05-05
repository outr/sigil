package sigil.tooling.types

import ch.epfl.scala.bsp4j.{Diagnostic, DiagnosticSeverity}
import fabric.rw.*

/** Sigil-flavored mirror of bsp4j's `Diagnostic`. Same shape as
  * [[LspDiagnostic]] except the severity uses bsp4j's enum values
  * (BSP and LSP align here in practice; we render to the same
  * [[LspSeverity]] enum so consumers can pattern-match across
  * sources uniformly). */
case class BspDiagnostic(filePath: String,
                         range: LspRange,
                         severity: LspSeverity,
                         message: String,
                         code: Option[String] = None,
                         source: Option[String] = None) derives RW

object BspDiagnostic {
  def fromBsp4j(filePath: String, d: Diagnostic): BspDiagnostic = {
    val sev = d.getSeverity match {
      case null                           => LspSeverity.Unknown
      case DiagnosticSeverity.ERROR       => LspSeverity.Error
      case DiagnosticSeverity.WARNING     => LspSeverity.Warning
      case DiagnosticSeverity.INFORMATION => LspSeverity.Information
      case DiagnosticSeverity.HINT        => LspSeverity.Hint
    }
    val rangeBsp = d.getRange
    val range = LspRange(
      start = LspPosition(rangeBsp.getStart.getLine.toInt + 1, rangeBsp.getStart.getCharacter.toInt + 1),
      end   = LspPosition(rangeBsp.getEnd.getLine.toInt + 1, rangeBsp.getEnd.getCharacter.toInt + 1)
    )
    // bsp4j's Diagnostic.code is Either[String, Integer] just like
    // LSP — flatten both sides into a String.
    val codeStr: Option[String] = Option(d.getCode).flatMap { c =>
      if (c.isLeft) Option(c.getLeft) else if (c.isRight) Option(c.getRight).map(_.toString) else None
    }
    BspDiagnostic(
      filePath = filePath,
      range    = range,
      severity = sev,
      message  = Option(d.getMessage).getOrElse(""),
      code     = codeStr,
      source   = Option(d.getSource)
    )
  }
}
