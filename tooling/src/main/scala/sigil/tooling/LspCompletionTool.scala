package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.CompletionItem
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{LspCompletionItem, LspCompletionResult}

case class LspCompletionInput(languageId: String,
                              filePath: String,
                              line: Int,
                              character: Int,
                              maxResults: Int = 50)
  extends ToolInput derives RW

/**
 * Request completion candidates at a source position. The server
 * returns ranked entries with optional details (type, kind, detail
 * string). Capped at `maxResults` so a giant Metals catalog doesn't
 * blow the agent's context.
 *
 * The agent uses this to discover what method names / fields / values
 * are valid at a cursor position — the same loop a human gets from
 * pressing Ctrl-Space. Far higher signal than scanning files for
 * naming conventions.
 */
final class LspCompletionTool(val manager: LspManager) extends Tool with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input = LspCompletionInput
  type Output = LspCompletionResult
  val inputRW = summon[RW[LspCompletionInput]]
  val outputRW = summon[RW[LspCompletionResult]]
  val name = ToolName("lsp_completion")
  val description =
    """Get completion candidates at a position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at the cursor location.
      |`maxResults` (default 50) caps the response so large catalogs don't flood context.
      |Returns `{filePath, items: [{label, kind, detail}], totalCount, truncated}`.""".stripMargin
  override val keywords = Set("lsp", "completion", "complete", "autocomplete", "suggest", "suggestion", "intellisense")

  override def executeOutput(input: LspCompletionInput, context: ToolContext): Task[LspCompletionResult] =
    withOpenDocumentOrThrow[LspCompletionResult](
      input.languageId,
      input.filePath,
      context
    ) { (session, uri) =>
      session.completion(uri, input.line, input.character).map { items =>
        val capped = items.take(input.maxResults).map(toItem)
        LspCompletionResult(
          filePath = input.filePath,
          items = capped,
          totalCount = items.size,
          truncated = items.size > input.maxResults
        )
      }
    }

  private def toItem(item: CompletionItem): LspCompletionItem =
    LspCompletionItem(
      label = item.getLabel,
      kind = Option(item.getKind).map(_.toString.toLowerCase),
      detail = Option(item.getDetail)
    )
}
