package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tooling.types.*

/**
 * Bug #9 phase 6 — verify Sigil-flavored typed shapes for LSP / BSP
 * tool emissions round-trip through fabric RW. Apps consuming the
 * wire shape pattern-match against these case classes; if the RW
 * derivation breaks, every consumer breaks silently.
 */
class ToolingTypedShapesSpec extends AnyWordSpec with Matchers {

  private def roundTrip[T](value: T)(using rw: RW[T]): T = rw.write(rw.read(value))

  "Bug #9 phase 6 typed shapes" should {
    "round-trip LspPosition" in {
      val v = LspPosition(line = 12, column = 7)
      roundTrip(v) shouldBe v
    }

    "round-trip LspRange" in {
      val v = LspRange(LspPosition(1, 1), LspPosition(2, 10))
      roundTrip(v) shouldBe v
    }

    "round-trip every LspSeverity case" in {
      List(LspSeverity.Error, LspSeverity.Warning, LspSeverity.Information,
           LspSeverity.Hint, LspSeverity.Unknown).foreach { v =>
        roundTrip(v) shouldBe v
      }
    }

    "round-trip LspDiagnostic with optional code/source populated" in {
      val v = LspDiagnostic(
        filePath = "/abs/Foo.scala",
        range    = LspRange(LspPosition(12, 5), LspPosition(12, 20)),
        severity = LspSeverity.Error,
        message  = "type mismatch",
        code     = Some("E007"),
        source   = Some("scalac")
      )
      roundTrip(v) shouldBe v
    }

    "round-trip LspDiagnostic with code/source absent" in {
      val v = LspDiagnostic(
        filePath = "/abs/Foo.scala",
        range    = LspRange(LspPosition(1, 1), LspPosition(1, 1)),
        severity = LspSeverity.Hint,
        message  = "unused import"
      )
      roundTrip(v) shouldBe v
    }

    "round-trip LspDiagnosticsResult with multiple diagnostics" in {
      val v = LspDiagnosticsResult(
        filePath = "/abs/Foo.scala",
        diagnostics = List(
          LspDiagnostic("/abs/Foo.scala", LspRange(LspPosition(10, 1), LspPosition(10, 5)),
                        LspSeverity.Error, "missing argument"),
          LspDiagnostic("/abs/Foo.scala", LspRange(LspPosition(15, 3), LspPosition(15, 8)),
                        LspSeverity.Warning, "deprecated", code = Some("0")),
        )
      )
      roundTrip(v) shouldBe v
    }

    "round-trip LspLocation" in {
      val v = LspLocation(
        uri      = "file:///abs/Foo.scala",
        filePath = "/abs/Foo.scala",
        range    = LspRange(LspPosition(42, 12), LspPosition(42, 25))
      )
      roundTrip(v) shouldBe v
    }

    "round-trip LspHover with markdown contents" in {
      val v = LspHover(
        contents = "**def** foo(x: Int): Int",
        kind     = "markdown",
        range    = Some(LspRange(LspPosition(1, 1), LspPosition(1, 10)))
      )
      roundTrip(v) shouldBe v
    }

    "round-trip BspDiagnostic + BspCompileResult" in {
      val v = BspCompileResult(
        projectRoot = "/abs/myproject",
        status      = "ERROR",
        targetCount = 3,
        diagnostics = List(BspDiagnostic(
          filePath = "/abs/myproject/Foo.scala",
          range    = LspRange(LspPosition(20, 1), LspPosition(20, 5)),
          severity = LspSeverity.Error,
          message  = "not found: Bar"
        ))
      )
      roundTrip(v) shouldBe v
    }
  }
}
