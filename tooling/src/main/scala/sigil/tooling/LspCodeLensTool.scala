package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.CodeLens
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{LspCodeLensItem, LspCodeLensResult, LspPosition}

case class LspCodeLensInput(languageId: String,
                            filePath: String) extends ToolInput derives RW

/**
 * List code lenses in a file — the small "Run | Debug" / "N
 * references" / etc. annotations editors render above method
 * declarations. Each lens carries a Command the agent can execute
 * via `lsp_apply_code_lens` (TBD — currently just listed; the
 * command-runner path is out of scope for the proof-of-concept).
 */
final class LspCodeLensTool(val manager: LspManager) extends TypedOutputTool[LspCodeLensInput, LspCodeLensResult](
  name = ToolName("lsp_code_lens"),
  description =
    """List code lenses in a file (run / debug / N-references / etc. annotations).
      |
      |`languageId` + `filePath` identify the document.
      |Returns each lens's position, optional title, and whether it carries a runnable command.""".stripMargin,
  keywords = Set("lsp", "code lens", "lens", "inline action", "above-line action")
) with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: LspCodeLensInput, context: TurnContext): Task[LspCodeLensResult] =
    withOpenDocumentTyped[LspCodeLensResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.codeLens(uri).map { lenses =>
        LspCodeLensResult(filePath = input.filePath, items = lenses.map(toItem))
      }
    }

  private def toItem(lens: CodeLens): LspCodeLensItem = {
    val cmd = Option(lens.getCommand)
    LspCodeLensItem(
      position   = LspPosition.fromLsp4j(lens.getRange.getStart),
      title      = cmd.flatMap(c => Option(c.getTitle)),
      hasCommand = cmd.isDefined
    )
  }
}
