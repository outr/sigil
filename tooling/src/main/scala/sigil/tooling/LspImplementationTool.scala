package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

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
 */
final class LspImplementationTool(val manager: LspManager) extends TypedTool[LspImplementationInput](
  name = ToolName("lsp_implementation"),
  description =
    """List concrete implementations of an abstract symbol (trait method, interface method, abstract def).
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point at the abstract symbol.""".stripMargin,
  examples = List(
    ToolExample(
      "find all overrides of an abstract method",
      LspImplementationInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 7)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspImplementationInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.implementation(uri, input.line, input.character).map { locations =>
        if (locations.isEmpty) "No implementations found."
        else locations.map { l =>
          val r = l.getRange
          s"  ${l.getUri} ${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
        }.mkString("\n")
      }
    }
}
