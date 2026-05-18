package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.CompletionItem
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
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
final class LspCompletionTool(val manager: LspManager)
  extends TypedOutputTool[LspCompletionInput, LspCompletionResult](
    name = ToolName("lsp_completion"),
    description =
      """Get completion candidates at a position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at the cursor location.
      |`maxResults` (default 50) caps the response so large catalogs don't flood context.
      |Returns `{filePath, items: [{label, kind, detail}], totalCount, truncated}`.""".stripMargin,
    keywords = Set("lsp", "completion", "complete", "autocomplete", "suggest", "suggestion", "intellisense"),
    examples = List(
      ToolExample(
        "scala completion at a method-call position",
        LspCompletionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 12)
      )
    )
  )
  with sigil.tool.ReadOnlyExternalTool
  with LspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: LspCompletionInput, context: TurnContext): Task[LspCompletionResult] =
    withOpenDocumentTyped[LspCompletionResult](
      input.languageId,
      input.filePath,
      context,
      onError = msg => throw new RuntimeException(msg)
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
