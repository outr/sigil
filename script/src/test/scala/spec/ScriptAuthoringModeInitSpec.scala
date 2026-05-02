package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.script.{
  ClassSignaturesTool,
  CreateScriptToolTool,
  DeleteScriptToolTool,
  LibraryLookupTool,
  ListScriptToolsTool,
  ReadSourceTool,
  ScriptAuthoringMode,
  UpdateScriptToolTool
}

/**
 * Regression coverage for bug #60 — `ScriptAuthoringMode` and the
 * script-authoring tools used to share a circular static-init
 * dependency: the mode's `tools` listed each tool by `.name`, and
 * each tool's super-constructor referenced `ScriptAuthoringMode.id`
 * via `modes`. Whichever side loaded first re-entered the other's
 * still-running `<clinit>` and read `MODULE$ == null`, throwing
 * `ExceptionInInitializerError` from the very first reference.
 *
 * **Lives in its own spec** so the per-suite forked JVM (`build.sbt`'s
 * `testGrouping`) gives this test a fresh classloader. If we had
 * folded these references into [[ScriptAuthoringModeSpec]], the spec's
 * earlier fixtures would have eagerly loaded both sides through
 * `Sigil.instance.sync()` and the regression would silently miss the
 * shape that crashes a cold-boot consumer (Dart codegen, Sage's
 * `Sigil.instance` boot, etc.).
 *
 * The fix references tool names as [[sigil.tool.ToolName]] literals on
 * the mode side; static-init becomes acyclic. This test is the cheap
 * canary that the invariant doesn't regress.
 */
class ScriptAuthoringModeInitSpec extends AnyWordSpec with Matchers {
  "ScriptAuthoringMode and its tools" should {
    "load without circular static-init when the mode is touched first" in {
      noException should be thrownBy {
        // Force the mode to initialise before any tool is referenced.
        // Pre-fix this entered ScriptAuthoringMode.<clinit>, which
        // computed `tools = List(LibraryLookupTool.name, …)`, which
        // re-entered LibraryLookupTool.<clinit>, which read
        // ScriptAuthoringMode.id while MODULE$ was still null → NPE.
        val _ = ScriptAuthoringMode.id
        // Then walk every tool — each one's super-constructor reads
        // ScriptAuthoringMode.id during its own <clinit>, which is now
        // safely a no-op against the already-initialised mode.
        val _ = LibraryLookupTool.name
        val _ = ClassSignaturesTool.name
        val _ = ReadSourceTool.name
        val _ = CreateScriptToolTool.name
        val _ = UpdateScriptToolTool.name
        val _ = DeleteScriptToolTool.name
        val _ = ListScriptToolsTool.name
      }
    }
  }
}
