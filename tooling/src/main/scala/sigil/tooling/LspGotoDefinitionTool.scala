package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspLocation

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
 * Emits `List[LspLocation]` — empty when no definition was found.
 */
final class LspGotoDefinitionTool(val manager: LspManager) extends TypedOutputTool[LspGotoDefinitionInput, List[LspLocation]](
  name = ToolName("lsp_goto_definition"),
  description =
    """Find where a symbol is defined.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the identifier.
      |Returns `[{uri, filePath, range:{start, end}}]` — empty when no definition found.""".stripMargin,
  keywords = Set(
    "lsp", "definition", "definitions", "where defined", "declaration",
    "jump-to", "goto", "go to", "find symbol", "examine", "inspect",
    "navigate", "source", "semantic", "symbol",
    "scala", "language", "code"
  ),
  examples = List(
    ToolExample(
      "scala goto-def at line 42 col 12",
      LspGotoDefinitionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 42, character = 12)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspGotoDefinitionInput, context: TurnContext): Task[List[LspLocation]] =
    withOpenDocumentTyped[List[LspLocation]](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.gotoDefinition(uri, input.line, input.character).map(_.map(LspLocation.fromLsp4j))
    }
}
