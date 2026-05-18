package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspLocation

case class LspFindReferencesInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int,
                                  includeDeclaration: Boolean = true,
                                  maxResults: Int = 200) extends ToolInput derives RW

/** Typed result for [[LspFindReferencesTool]]. `truncated = true`
  * when the server returned more locations than `maxResults`; the
  * `locations` list reflects the post-cap slice. */
case class LspFindReferencesOutput(locations: List[LspLocation],
                                   truncated: Boolean) derives RW

/**
 * Find every usage of a symbol across the workspace. The server
 * resolves through actual symbol identity (not text match), so a
 * `Foo` reference is found regardless of imports / aliases /
 * partial qualification.
 *
 * Capped at `maxResults` to keep huge codebases from blowing the
 * agent's context. `truncated` is true when the cap fired.
 */
final class LspFindReferencesTool(val manager: LspManager) extends TypedOutputTool[LspFindReferencesInput, LspFindReferencesOutput](
  name = ToolName("lsp_find_references"),
  description =
    """Find every usage of a symbol across the workspace.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point inside the symbol.
      |`includeDeclaration` (default true) — whether to include the symbol's own definition.
      |`maxResults` (default 200) — caps the response.
      |
      |Returns `{locations: [{uri, filePath, range}], truncated}`.""".stripMargin,
  keywords = Set(
    "lsp", "references", "usages", "callers", "who calls", "find usage", "find all",
    "occurrences", "examine", "inspect", "analyze", "review", "uses",
    "where used", "find symbol", "semantic",
    "scala", "language", "code", "navigate"
  )
) with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: LspFindReferencesInput, context: TurnContext): Task[LspFindReferencesOutput] =
    withOpenDocumentTyped[LspFindReferencesOutput](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.references(uri, input.line, input.character, input.includeDeclaration).map { locations =>
        val capped = locations.take(input.maxResults)
        LspFindReferencesOutput(
          locations = capped.map(LspLocation.fromLsp4j),
          truncated = locations.size > input.maxResults
        )
      }
    }
}
