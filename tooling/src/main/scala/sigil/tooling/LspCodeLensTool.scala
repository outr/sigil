package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.CodeLens
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspCodeLensInput(languageId: String,
                            filePath: String) extends ToolInput derives RW

/**
 * List code lenses in a file — the small "Run | Debug" / "N
 * references" / etc. annotations editors render above method
 * declarations. Each lens carries a Command the agent can execute
 * via `lsp_apply_code_lens` (TBD — currently just listed; the
 * command-runner path is out of scope for the proof-of-concept).
 */
final class LspCodeLensTool(val manager: LspManager) extends TypedTool[LspCodeLensInput](
  name = ToolName("lsp_code_lens"),
  description =
    """List code lenses in a file (run / debug / N-references / etc. annotations).
      |
      |`languageId` + `filePath` identify the document.
      |Each entry shows position, command (if any), and title.""".stripMargin,
  examples = List(
    ToolExample(
      "list lenses on a Scala file",
      LspCodeLensInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspCodeLensInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.codeLens(uri).map { lenses =>
        if (lenses.isEmpty) "No code lenses."
        else lenses.map(render).mkString("\n")
      }
    }

  private def render(lens: CodeLens): String = {
    val r = lens.getRange
    val pos = s"${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
    val title = Option(lens.getCommand).map(_.getTitle).getOrElse("(no command)")
    s"  $pos: $title"
  }
}
