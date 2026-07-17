package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tooling.types.{
  BspCompileResult, BspDiagnostic, LspDiagnostic, LspDiagnosticsResult,
  LspPosition, LspRange, LspSeverity
}

/**
 * A tool result must stay useful under head-truncation — the
 * externalization layer keeps the FIRST bytes of the rendered text,
 * so a single-line JSON render dies mid-first-diagnostic on exactly
 * the runs with the most errors. `modelText` renders compile/
 * diagnostic results line-oriented: verdict + counts on line 1
 * (survives ANY truncation), one diagnostic per line after it,
 * errors before warnings, grouped by file.
 */
class DiagnosticRenderSpec extends AnyWordSpec with Matchers {

  private def range(line: Int) = LspRange(LspPosition(line, 5), LspPosition(line, 9))

  private def bspDiag(file: String, line: Int, message: String,
                      severity: LspSeverity = LspSeverity.Error): BspDiagnostic =
    BspDiagnostic(filePath = file, range = range(line), severity = severity, message = message)

  private def lspDiag(file: String, line: Int, message: String,
                      severity: LspSeverity = LspSeverity.Error): LspDiagnostic =
    LspDiagnostic(filePath = file, range = range(line), severity = severity, message = message)

  "BspCompileResult.modelText" should {

    "stay actionable when truncated to its first 15 lines" in {
      val diags = (1 to 30).toList.map(i => bspDiag(s"src/File${i % 9}.scala", i, s"error number $i")) :::
        (1 to 7).toList.map(i => bspDiag(s"src/File$i.scala", i, s"warning $i", LspSeverity.Warning))
      val result = BspCompileResult("/proj", "ERROR", 25, diags)
      val lines = result.modelText.get.split('\n').toList
      val head15 = lines.take(15)

      // Line 1 carries the verdict + counts regardless of truncation.
      head15.head should include ("ERROR")
      head15.head should include ("30 error(s)")
      head15.head should include ("7 warning(s)")
      head15.head should include ("25 target(s)")
      // ≥10 complete file:line: error entries inside the head.
      head15.tail.count(_.matches(""".+\.scala:\d+:\d+: error: .+""")) should be >= 10
      // Errors render before any warning.
      val firstWarning = lines.indexWhere(_.contains(": warning: "))
      val lastError    = lines.lastIndexWhere(_.contains(": error: "))
      lastError should be < firstWarning
    }

    "keep the status line as line 1 and group diagnostics by file" in {
      val diags = List(
        bspDiag("src/B.scala", 9, "late"),
        bspDiag("src/A.scala", 3, "early"),
        bspDiag("src/B.scala", 2, "first in B")
      )
      val lines = BspCompileResult("/proj", "ERROR", 3, diags).modelText.get.split('\n').toList
      lines.head should startWith ("ERROR")
      lines.drop(1) shouldBe List(
        "src/A.scala:3:5: error: early",
        "src/B.scala:2:5: error: first in B",
        "src/B.scala:9:5: error: late"
      )
    }

    "render a clean compile as a single status line" in {
      BspCompileResult("/proj", "OK", 25, Nil).modelText.get shouldBe "OK · 25 target(s)"
    }

    "put a cause right after the status when there are no diagnostics, and last when there are" in {
      val bare = BspCompileResult("/proj", "ERROR", 0, Nil, cause = Some("BSP error: connection reset"))
      bare.modelText.get.split('\n').toList shouldBe List(
        "ERROR · 0 target(s)",
        "cause: BSP error: connection reset"
      )
      val withDiags = BspCompileResult("/proj", "ERROR", 2,
        List(bspDiag("src/A.scala", 1, "boom")), cause = Some("request also failed"))
      val lines = withDiags.modelText.get.split('\n').toList
      lines(1) should include ("boom")
      lines.last shouldBe "cause: request also failed"
    }

    "indent multi-line messages so every diagnostic starts at column zero" in {
      val diags = List(
        bspDiag("src/A.scala", 1, "unclosed comment\n  |/* opened here\n  |^"),
        bspDiag("src/A.scala", 8, "second")
      )
      val lines = BspCompileResult("/proj", "ERROR", 1, diags).modelText.get.split('\n').toList
      lines(1) shouldBe "src/A.scala:1:5: error: unclosed comment"
      lines(2) should startWith ("  ")
      // The next diagnostic's primary line is back at column zero.
      lines.exists(_.startsWith("src/A.scala:8:5: error: second")) shouldBe true
    }
  }

  "LspDiagnosticsResult.modelText" should {

    "declare a fresh empty result clean" in {
      LspDiagnosticsResult("src/A.scala", Nil, fresh = true).modelText.get should
        include ("src/A.scala is clean")
    }

    "refuse to call a stale empty snapshot clean" in {
      val text = LspDiagnosticsResult("src/A.scala", Nil, fresh = false).modelText.get
      text should include ("freshness UNKNOWN")
      text should include ("do NOT treat as clean")
      text should not include "is clean"
    }

    "lead with counts and mark a stale non-empty snapshot" in {
      val diags = List(lspDiag("src/A.scala", 4, "boom"), lspDiag("src/A.scala", 2, "meh", LspSeverity.Warning))
      val freshLines = LspDiagnosticsResult("src/A.scala", diags, fresh = true).modelText.get.split('\n').toList
      freshLines.head should include ("1 error(s), 1 warning(s) in src/A.scala")
      freshLines.head should not include "STALE"
      freshLines(1) shouldBe "src/A.scala:4:5: error: boom"

      val stale = LspDiagnosticsResult("src/A.scala", diags, fresh = false).modelText.get
      stale.split('\n').head should include ("STALE snapshot")
    }
  }
}
