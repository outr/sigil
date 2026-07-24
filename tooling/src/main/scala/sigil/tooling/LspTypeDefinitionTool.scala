package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{LspLocation, LspLocationsResult}

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
 * a method's return shape. Emits `LspLocationsResult`.
 */
final class LspTypeDefinitionTool(val manager: LspManager) extends Tool with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input = LspTypeDefinitionInput
  type Output = LspLocationsResult
  val inputRW = summon[RW[LspTypeDefinitionInput]]
  val outputRW = summon[RW[LspLocationsResult]]

  val name = ToolName("lsp_type_definition")
  val description =
    """Find the type of a symbol — its class / trait / struct definition. Distinct from
      |the symbol's own definition: this resolves to the symbol's TYPE.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point at the symbol whose type to look up.
      |Returns `[{uri, filePath, range}]`.""".stripMargin
  override val keywords = Set("lsp", "type definition", "type", "where defined", "type declaration", "examine", "inspect")

  override def executeOutput(input: LspTypeDefinitionInput, context: ToolContext): Task[LspLocationsResult] =
    withOpenDocumentOrThrow[LspLocationsResult](
      input.languageId,
      input.filePath,
      context
    ) { (session, uri) =>
      session.typeDefinition(uri, input.line, input.character)
        .map(locs => LspLocationsResult(locs.map(LspLocation.fromLsp4j)))
    }
}
