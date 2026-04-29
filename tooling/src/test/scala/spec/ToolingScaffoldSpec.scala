package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId}
import sigil.tooling.*

/**
 * Smoke check for the `sigil-tooling` scaffold — every config record
 * and tool-input case class round-trips through its derived RW.
 * Doesn't spawn any subprocess (that needs a real Metals / sbt
 * install); the LSP / BSP request paths are exercised by integration
 * specs apps add when they wire a concrete server.
 */
class ToolingScaffoldSpec extends AnyWordSpec with Matchers {
  // SpaceId is an open PolyType; the framework normally registers
  // GlobalSpace inside Sigil.polymorphicRegistrations. This spec
  // doesn't construct a Sigil so we register the framework sentinel
  // directly — same effect, just localized to the test process.
  SpaceId.register(RW.static[SpaceId](GlobalSpace))

  private def roundTrip[T: RW](value: T): Unit = {
    val rw = summon[RW[T]]
    rw.write(rw.read(value)) shouldBe value
  }

  "LspServerConfig" should {
    "round-trip via its derived RW" in {
      roundTrip(LspServerConfig(
        languageId = "scala",
        command = "metals",
        args = List("-J-Xmx2G"),
        rootMarkers = List("build.sbt", "build.sc")
      ))
    }
  }

  "BspBuildConfig" should {
    "round-trip via its derived RW" in {
      roundTrip(BspBuildConfig(
        projectRoot = "/abs/path/myproject",
        command = "sbt",
        args = List("-bsp")
      ))
    }
  }

  "LSP tool inputs" should {
    "round-trip every shape the framework ships" in {
      roundTrip(LspDiagnosticsInput(languageId = "scala", filePath = "/abs/Foo.scala", waitMs = 500L))
      roundTrip(LspGotoDefinitionInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5))
      roundTrip(LspHoverInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5))
      roundTrip(LspDidChangeInput(languageId = "scala", filePath = "/abs/Foo.scala", text = "object Foo"))
      roundTrip(LspCompletionInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5))
      roundTrip(LspSignatureHelpInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5))
      roundTrip(LspCodeActionInput(
        languageId = "scala", filePath = "/abs/Foo.scala",
        startLine = 10, startCharacter = 0, endLine = 10, endCharacter = 0,
        onlyKinds = List("quickfix")
      ))
      roundTrip(LspApplyCodeActionInput(languageId = "scala", filePath = "/abs/Foo.scala", index = 0))
      roundTrip(LspFormatInput(languageId = "scala", filePath = "/abs/Foo.scala"))
      roundTrip(LspFormatRangeInput(
        languageId = "scala", filePath = "/abs/Foo.scala",
        startLine = 0, startCharacter = 0, endLine = 5, endCharacter = 0
      ))
      roundTrip(LspRenameInput(
        languageId = "scala", filePath = "/abs/Foo.scala",
        line = 10, character = 5, newName = "renamed"
      ))
      roundTrip(LspPrepareRenameInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5))
      roundTrip(LspFindReferencesInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5))
      roundTrip(LspTypeDefinitionInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5))
      roundTrip(LspImplementationInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5))
      roundTrip(LspDocumentSymbolsInput(languageId = "scala", filePath = "/abs/Foo.scala"))
      roundTrip(LspWorkspaceSymbolsInput(languageId = "scala", projectRoot = "/abs/path", query = "Provider"))
      roundTrip(LspFoldingRangeInput(languageId = "scala", filePath = "/abs/Foo.scala"))
      roundTrip(LspSelectionRangeInput(
        languageId = "scala", filePath = "/abs/Foo.scala",
        positions = List(LspSelectionRangeInput.Pos(line = 10, character = 5))
      ))
      roundTrip(LspPullDiagnosticsInput(languageId = "scala", filePath = "/abs/Foo.scala"))
      roundTrip(LspInlayHintsInput(languageId = "scala", filePath = "/abs/Foo.scala"))
      roundTrip(LspCodeLensInput(languageId = "scala", filePath = "/abs/Foo.scala"))
      roundTrip(LspDocumentLinkInput(languageId = "scala", filePath = "/abs/Foo.scala"))
    }
  }

  "BSP tool inputs" should {
    "round-trip every shape the framework ships" in {
      roundTrip(BspCompileInput(projectRoot = "/abs/path", targets = List("file:///abs/path/?id=core")))
      roundTrip(BspListTargetsInput(projectRoot = "/abs/path"))
      roundTrip(BspTestInput(
        projectRoot = "/abs/path",
        targets = List("file:///abs/path/?id=core"),
        arguments = List("-z", "Suite")
      ))
      roundTrip(BspRunInput(projectRoot = "/abs/path", target = "file:///abs/path/?id=core"))
      roundTrip(BspCleanInput(projectRoot = "/abs/path"))
      roundTrip(BspReloadInput(projectRoot = "/abs/path"))
      roundTrip(BspSourcesInput(projectRoot = "/abs/path"))
      roundTrip(BspInverseSourcesInput(projectRoot = "/abs/path", filePath = "/abs/path/Foo.scala"))
      roundTrip(BspDependencySourcesInput(projectRoot = "/abs/path"))
      roundTrip(BspDependencyModulesInput(projectRoot = "/abs/path"))
      roundTrip(BspResourcesInput(projectRoot = "/abs/path"))
      roundTrip(BspOutputPathsInput(projectRoot = "/abs/path"))
      roundTrip(BspScalacOptionsInput(projectRoot = "/abs/path"))
      roundTrip(BspScalaTestClassesInput(projectRoot = "/abs/path"))
      roundTrip(BspScalaMainClassesInput(projectRoot = "/abs/path"))
    }
  }

  "WorkspaceEditApplier.applyTextEdits" should {
    "apply non-overlapping edits back-to-front" in {
      import org.eclipse.lsp4j.{Position, Range, TextEdit}
      val text = "abcdefghij"
      val edits = List(
        new TextEdit(new Range(new Position(0, 0), new Position(0, 1)), "X"),  // a → X
        new TextEdit(new Range(new Position(0, 5), new Position(0, 6)), "Y")   // f → Y
      )
      WorkspaceEditApplier.applyTextEdits(text, edits) shouldBe "XbcdeYghij"
    }
  }
}
