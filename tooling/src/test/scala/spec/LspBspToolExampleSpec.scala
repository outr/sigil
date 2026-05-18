package spec

import fabric.{Arr, Json, Obj, Str}
import fabric.io.JsonFormatter
import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.{Tool, ToolInput}
import sigil.tooling.{
  BspCleanTool, BspCompileTool, BspDependencyModulesTool, BspDependencySourcesTool,
  BspInverseSourcesTool, BspListTargetsTool, BspManager, BspOutputPathsTool, BspReloadTool,
  BspResourcesTool, BspRunTool, BspScalacOptionsTool, BspScalaMainClassesTool,
  BspScalaTestClassesTool, BspSourcesTool, BspTestTool,
  LspApplyCodeActionTool, LspCodeActionTool, LspCodeLensTool, LspCompletionTool,
  LspDiagnosticsTool, LspDidChangeTool, LspDocumentLinkTool, LspDocumentSymbolsTool,
  LspFindReferencesTool, LspFoldingRangeTool, LspFormatRangeTool, LspFormatTool,
  LspGotoDefinitionTool, LspHoverTool, LspImplementationTool, LspInlayHintsTool, LspManager,
  LspPrepareRenameTool, LspPullDiagnosticsTool, LspRenameSymbolTool, LspRenameTool,
  LspSelectionRangeTool, LspSignatureHelpTool, LspTypeDefinitionTool, LspWorkspaceSymbolsTool
}
import sigil.tooling.dispatch.DispatchWorkersTool

/**
 * Regression spec for sigil bug #227 — every LSP / BSP tool example
 * value used to carry fabricated placeholder paths (`/abs/path/Foo.scala`,
 * `/abs/path/myproject`) which agents copied verbatim into real tool
 * calls. Layer-1 fix: drop the placeholder examples entirely; the
 * universal navigation tools `next_page` / `query_tool_output` get
 * no examples either (otherwise rendered schema-type names like
 * `"string"` leak as values).
 *
 * For every LSP / BSP tool plus the two pagination tools, render each
 * example's input via the tool's `inputRW` and assert no string leaf
 * contains the placeholder fingerprints or the JSON-schema type-name
 * sentinels.
 */
class LspBspToolExampleSpec extends AnyWordSpec with Matchers {

  /** Tools whose examples must not leak placeholders. Built from
    * direct construction (no LSP / BSP subprocess required — we only
    * read metadata) so the spec stays a pure unit test. */
  private val auditedTools: List[Tool] = {
    val fs = new sigil.tool.fs.LocalFileSystemContext(basePath = None)
    val lsp = null.asInstanceOf[LspManager]
    val bsp = null.asInstanceOf[BspManager]
    // `fs` is referenced indirectly via the dispatch tool's
    // `FromCall`/`FromFile` paths but doesn't need to be live for
    // this metadata-only audit.
    val _ = fs
    List(
      // LSP family — every tool registered by ToolingSigil.lspTools
      new LspDiagnosticsTool(lsp),
      new LspGotoDefinitionTool(lsp),
      new LspHoverTool(lsp),
      new LspDidChangeTool(lsp),
      new LspCompletionTool(lsp),
      new LspSignatureHelpTool(lsp),
      new LspCodeActionTool(lsp),
      new LspApplyCodeActionTool(lsp),
      new LspFormatTool(lsp),
      new LspFormatRangeTool(lsp),
      new LspRenameTool(lsp),
      new LspPrepareRenameTool(lsp),
      new LspFindReferencesTool(lsp),
      new LspTypeDefinitionTool(lsp),
      new LspImplementationTool(lsp),
      new LspDocumentSymbolsTool(lsp),
      new LspWorkspaceSymbolsTool(lsp),
      new LspFoldingRangeTool(lsp),
      new LspSelectionRangeTool(lsp),
      new LspPullDiagnosticsTool(lsp),
      new LspInlayHintsTool(lsp),
      new LspCodeLensTool(lsp),
      new LspDocumentLinkTool(lsp),
      // BSP family — every tool registered by ToolingSigil.bspTools
      new BspListTargetsTool(bsp),
      new BspCompileTool(bsp),
      new BspTestTool(bsp),
      new BspRunTool(bsp),
      new BspCleanTool(bsp),
      new BspReloadTool(bsp),
      new BspSourcesTool(bsp),
      new BspInverseSourcesTool(bsp),
      new BspDependencySourcesTool(bsp),
      new BspDependencyModulesTool(bsp),
      new BspResourcesTool(bsp),
      new BspOutputPathsTool(bsp),
      new BspScalacOptionsTool(bsp),
      new BspScalaTestClassesTool(bsp),
      new BspScalaMainClassesTool(bsp),
      // Rename + dispatch — wrap LSP / framework primitives under
      // the hood and the agent reads their examples verbatim.
      new LspRenameSymbolTool(lsp),
      new DispatchWorkersTool(),
      // Pagination navigation — bug #227 also called these out for
      // leaking schema-type strings in their rendered example slot
      sigil.tool.output.NextPageTool,
      sigil.tool.output.QueryToolOutputTool
    )
  }

  /** Placeholder fragments that must never appear in any example's
    * rendered JSON — these are the fingerprints from the field repro. */
  private val placeholderFragments: List[String] =
    List("/abs/path", "Foo.scala", "myproject")

  /** JSON-schema type-name sentinels — when a renderer falls back to
    * "describe the schema" instead of "render an example value", these
    * leak through as literal string values like `"string"` / `"integer"`
    * which agents then copy verbatim. */
  private val schemaTypeNameSentinels: Set[String] =
    Set("string", "integer", "boolean", "number", "object", "array")

  /** Walk a fabric Json tree and collect every string leaf value. */
  private def stringLeaves(json: Json): List[String] = json match {
    case s: Str => List(s.value)
    case o: Obj => o.value.values.toList.flatMap(stringLeaves)
    case a: Arr => a.value.toList.flatMap(stringLeaves)
    case _      => Nil
  }

  private def renderInput(tool: Tool, input: ToolInput): Json =
    tool.inputRW.asInstanceOf[RW[ToolInput]].read(input)

  "LSP / BSP / pagination tool examples" should {
    "contain no placeholder paths or filenames from bug #227" in {
      val violations: List[String] = auditedTools.flatMap { tool =>
        tool.examples.flatMap { example =>
          val json     = renderInput(tool, example.input)
          val rendered = JsonFormatter.Compact(json)
          placeholderFragments.collect {
            case fragment if rendered.contains(fragment) =>
              s"${tool.name.value} example ${example.description.inspect}: " +
                s"rendered JSON contains placeholder fragment ${fragment.inspect} — $rendered"
          }
        }
      }
      withClue(violations.mkString("\n")) {
        violations shouldBe empty
      }
    }

    "contain no JSON-schema type names as string values" in {
      val violations: List[String] = auditedTools.flatMap { tool =>
        tool.examples.flatMap { example =>
          val json    = renderInput(tool, example.input)
          val leaves  = stringLeaves(json)
          leaves.collect {
            case leaf if schemaTypeNameSentinels.contains(leaf) =>
              s"${tool.name.value} example ${example.description.inspect}: " +
                s"contains schema type-name sentinel ${leaf.inspect} — ${JsonFormatter.Compact(json)}"
          }
        }
      }
      withClue(violations.mkString("\n")) {
        violations shouldBe empty
      }
    }
  }

  extension (s: String)
    private def inspect: String = s""""$s""""
}
