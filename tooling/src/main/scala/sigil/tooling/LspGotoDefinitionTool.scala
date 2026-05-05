package sigil.tooling

import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
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
 */
final class LspGotoDefinitionTool(val manager: LspManager) extends TypedTool[LspGotoDefinitionInput](
  name = ToolName("lsp_goto_definition"),
  description =
    """Find where a symbol is defined.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the identifier.
      |Returns one or more file:line:character locations.""".stripMargin,
  examples = List(
    ToolExample(
      "scala goto-def at line 42 col 12",
      LspGotoDefinitionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 42, character = 12)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspGotoDefinitionInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.gotoDefinition(uri, input.line, input.character).map { locations =>
        val typed = locations.map(LspLocation.fromLsp4j)
        JsonFormatter.Compact(summon[RW[List[LspLocation]]].read(typed))
      }
    }
}
