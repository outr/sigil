package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{LspLocation, LspLocationsResult}

case class LspGotoDefinitionInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int) extends ToolInput derives RW

/**
 * Locate where a symbol is defined. `line` and `character` are 0-based
 * (LSP convention) and identify a position inside an identifier in
 * the source file. The server returns one or more file URIs with
 * ranges — usually one for primary definitions, multiple for
 * overloaded methods or partial-class members.
 *
 * Higher-leverage than a `grep_definition` regex search because the
 * language server resolves through the actual symbol resolution
 * graph (imports, type aliases, generics) — finds the right Foo
 * when there are nine `Foo` in scope.
 *
 * Emits `LspLocationsResult` — empty when no definition was found.
 */
final class LspGotoDefinitionTool(val manager: LspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input  = LspGotoDefinitionInput
  type Output = LspLocationsResult
  val inputRW  = summon[RW[LspGotoDefinitionInput]]
  val outputRW = summon[RW[LspLocationsResult]]

  val name = ToolName("lsp_goto_definition")
  val description =
    """Find where a symbol is defined.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the identifier.
      |Returns `[{uri, filePath, range:{start, end}}]` — empty when no definition found.""".stripMargin
  override val keywords = Set(
    "lsp", "definition", "definitions", "where defined", "declaration",
    "jump-to", "goto", "go to", "find symbol", "examine", "inspect",
    "navigate", "source", "semantic", "symbol",
    "scala", "language", "code"
  )


  override def executeOutput(input: LspGotoDefinitionInput, context: ToolContext): Task[LspLocationsResult] =
    withOpenDocumentOrThrow[LspLocationsResult](
      input.languageId, input.filePath, context
    ) { (session, uri) =>
      session.gotoDefinition(uri, input.line, input.character)
        .map(locs => LspLocationsResult(locs.map(LspLocation.fromLsp4j)))
    }
}
