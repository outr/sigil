package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.CompletionItem
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspCompletionInput(languageId: String,
                              filePath: String,
                              line: Int,
                              character: Int,
                              maxResults: Int = 50) extends ToolInput derives RW

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
final class LspCompletionTool(val manager: LspManager) extends TypedTool[LspCompletionInput](
  name = ToolName("lsp_completion"),
  description =
    """Get completion candidates at a position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at the cursor location.
      |`maxResults` (default 50) caps the response so large catalogs don't flood context.""".stripMargin,
  examples = List(
    ToolExample(
      "scala completion at a method-call position",
      LspCompletionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 12)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspCompletionInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.completion(uri, input.line, input.character).map { items =>
        if (items.isEmpty) "No completions."
        else {
          val capped = items.take(input.maxResults)
          val rendered = capped.map(render).mkString("\n")
          if (items.size > input.maxResults)
            s"$rendered\n... (${items.size - input.maxResults} more, tighten by raising maxResults)"
          else rendered
        }
      }
    }

  private def render(item: CompletionItem): String = {
    val detail = Option(item.getDetail).map(d => s" — $d").getOrElse("")
    val kind = Option(item.getKind).map(_.toString.toLowerCase).getOrElse("?")
    s"  [$kind] ${item.getLabel}$detail"
  }
}
