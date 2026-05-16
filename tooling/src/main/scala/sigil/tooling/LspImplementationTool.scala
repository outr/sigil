package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspLocation

case class LspImplementationInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int) extends ToolInput derives RW

/**
 * For a trait / interface / abstract method position, list every
 * concrete implementation across the workspace. Inverse of
 * goto-definition for inheritance hierarchies — `goto_definition`
 * lands on the abstract method, `implementation` lands on each
 * subclass's override.
 *
 * Emits `List[LspLocation]`; empty when no implementations.
 */
final class LspImplementationTool(val manager: LspManager) extends TypedOutputTool[LspImplementationInput, List[LspLocation]](
  name = ToolName("lsp_implementation"),
  description =
    """List concrete implementations of an abstract symbol (trait method, interface method, abstract def).
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point at the abstract symbol.
      |Returns `[{uri, filePath, range}]`.""".stripMargin,
  keywords = Set(
    "lsp", "implementation", "implementations", "who implements", "implementors",
    "concrete", "subclasses", "traits", "interface", "examine", "inspect"
  ),
  examples = List(
    ToolExample(
      "find all overrides of an abstract method",
      LspImplementationInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 7)
    )
  )
) with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: LspImplementationInput, context: TurnContext): Task[List[LspLocation]] =
    withOpenDocumentTyped[List[LspLocation]](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.implementation(uri, input.line, input.character).map(_.map(LspLocation.fromLsp4j))
    }
}
