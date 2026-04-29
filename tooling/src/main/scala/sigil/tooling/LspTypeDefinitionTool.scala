package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspTypeDefinitionInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int) extends ToolInput derives RW

/**
 * Locate the *type* of a symbol — the class/trait/struct it
 * belongs to. Distinct from `lsp_goto_definition` which locates the
 * symbol itself: for `val x: Foo = ...`, goto-definition lands on
 * `x`, type-definition lands on `Foo`.
 *
 * Useful when the agent needs to read the type's source to understand
 * a method's return shape.
 */
final class LspTypeDefinitionTool(val manager: LspManager) extends TypedTool[LspTypeDefinitionInput](
  name = ToolName("lsp_type_definition"),
  description =
    """Find the type of a symbol — its class / trait / struct definition.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point at the symbol whose type to look up.
      |Distinct from `lsp_goto_definition`, which finds the symbol itself.""".stripMargin,
  examples = List(
    ToolExample(
      "find the type of a value",
      LspTypeDefinitionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 7)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspTypeDefinitionInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.typeDefinition(uri, input.line, input.character).map { locations =>
        if (locations.isEmpty) "No type definition found."
        else locations.map { l =>
          val r = l.getRange
          s"  ${l.getUri} ${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
        }.mkString("\n")
      }
    }
}
