package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspFindReferencesInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int,
                                  includeDeclaration: Boolean = true,
                                  maxResults: Int = 200) extends ToolInput derives RW

/**
 * Find every usage of a symbol across the workspace. The server
 * resolves through actual symbol identity (not text match), so a
 * `Foo` reference is found regardless of imports / aliases /
 * partial qualification.
 *
 * Capped at `maxResults` to keep huge codebases from blowing the
 * agent's context. The summary line indicates when the cap fired.
 */
final class LspFindReferencesTool(val manager: LspManager) extends TypedTool[LspFindReferencesInput](
  name = ToolName("lsp_find_references"),
  description =
    """Find every usage of a symbol across the workspace.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point inside the symbol.
      |`includeDeclaration` (default true) — whether to include the symbol's own definition.
      |`maxResults` (default 200) — caps the response.""".stripMargin,
  examples = List(
    ToolExample(
      "find references to a method",
      LspFindReferencesInput(
        languageId = "scala", filePath = "/abs/path/Foo.scala",
        line = 10, character = 7
      )
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspFindReferencesInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.references(uri, input.line, input.character, input.includeDeclaration).map { locations =>
        if (locations.isEmpty) "No references found."
        else {
          val capped = locations.take(input.maxResults)
          val rendered = capped.map { l =>
            val r = l.getRange
            s"  ${l.getUri} ${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
          }.mkString("\n")
          if (locations.size > input.maxResults)
            s"$rendered\n... (${locations.size - input.maxResults} more, raise maxResults to see all)"
          else rendered
        }
      }
    }
}
