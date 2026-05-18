package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspLocation

case class LspTypeDefinitionInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int)
  extends ToolInput derives RW

/**
 * Locate the *type* of a symbol — the class/trait/struct it
 * belongs to. Distinct from `lsp_goto_definition` which locates the
 * symbol itself: for `val x: Foo = ...`, goto-definition lands on
 * `x`, type-definition lands on `Foo`.
 *
 * Useful when the agent needs to read the type's source to understand
 * a method's return shape. Emits `List[LspLocation]`.
 */
final class LspTypeDefinitionTool(val manager: LspManager)
  extends TypedOutputTool[LspTypeDefinitionInput, List[LspLocation]](
    name = ToolName("lsp_type_definition"),
    description =
      """Find the type of a symbol — its class / trait / struct definition. Distinct from
      |the symbol's own definition: this resolves to the symbol's TYPE.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point at the symbol whose type to look up.
      |Returns `[{uri, filePath, range}]`.""".stripMargin,
    keywords = Set("lsp", "type definition", "type", "where defined", "type declaration", "examine", "inspect"),
    examples = List(
      ToolExample(
        "find the type of a value",
        LspTypeDefinitionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 7)
      )
    )
  )
  with sigil.tool.ReadOnlyExternalTool
  with LspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: LspTypeDefinitionInput, context: TurnContext): Task[List[LspLocation]] =
    withOpenDocumentTyped[List[LspLocation]](
      input.languageId,
      input.filePath,
      context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.typeDefinition(uri, input.line, input.character).map(_.map(LspLocation.fromLsp4j))
    }
}
