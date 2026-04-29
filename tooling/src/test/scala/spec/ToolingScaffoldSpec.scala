package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId}
import sigil.tooling.*

/**
 * Smoke check for the `sigil-tooling` scaffold — config records and
 * tool-input case classes round-trip through their derived RWs.
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

  "LspServerConfig" should {
    "round-trip via its derived RW" in {
      val cfg = LspServerConfig(
        languageId = "scala",
        command = "metals",
        args = List("-J-Xmx2G"),
        rootMarkers = List("build.sbt", "build.sc")
      )
      val rw = summon[RW[LspServerConfig]]
      rw.write(rw.read(cfg)) shouldBe cfg
    }
  }

  "BspBuildConfig" should {
    "round-trip via its derived RW" in {
      val cfg = BspBuildConfig(
        projectRoot = "/abs/path/myproject",
        command = "sbt",
        args = List("-bsp")
      )
      val rw = summon[RW[BspBuildConfig]]
      rw.write(rw.read(cfg)) shouldBe cfg
    }
  }

  "Tool inputs" should {
    "round-trip LspDiagnosticsInput" in {
      val in = LspDiagnosticsInput(languageId = "scala", filePath = "/abs/Foo.scala", waitMs = 500L)
      val rw = summon[RW[LspDiagnosticsInput]]
      rw.write(rw.read(in)) shouldBe in
    }
    "round-trip LspGotoDefinitionInput" in {
      val in = LspGotoDefinitionInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5)
      val rw = summon[RW[LspGotoDefinitionInput]]
      rw.write(rw.read(in)) shouldBe in
    }
    "round-trip LspHoverInput" in {
      val in = LspHoverInput(languageId = "scala", filePath = "/abs/Foo.scala", line = 10, character = 5)
      val rw = summon[RW[LspHoverInput]]
      rw.write(rw.read(in)) shouldBe in
    }
    "round-trip BspCompileInput" in {
      val in = BspCompileInput(projectRoot = "/abs/path", targets = List("file:///abs/path/?id=core"))
      val rw = summon[RW[BspCompileInput]]
      rw.write(rw.read(in)) shouldBe in
    }
  }

}
