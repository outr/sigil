package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{Tool, ToolInput, ToolName}
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
                                   truncated: Boolean) extends sigil.tool.ToolOutput derives RW

/**
 * Find every usage of a symbol across the workspace. The server
 * resolves through actual symbol identity (not text match), so a
 * `Foo` reference is found regardless of imports / aliases /
 * partial qualification.
 *
 * Capped at `maxResults` to keep huge codebases from blowing the
 * agent's context. `truncated` is true when the cap fired.
 */
final class LspFindReferencesTool(val manager: LspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input  = LspFindReferencesInput
  type Output = LspFindReferencesOutput
  val inputRW  = summon[RW[LspFindReferencesInput]]
  val outputRW = summon[RW[LspFindReferencesOutput]]
  val name = ToolName("lsp_find_references")
  val description =
    """Find every usage of a symbol across the workspace.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point inside the symbol.
      |`includeDeclaration` (default true) — whether to include the symbol's own definition.
      |`maxResults` (default 200) — caps the response.
      |
      |Returns `{locations: [{uri, filePath, range}], truncated}`.""".stripMargin
  override val keywords = Set(
    "lsp", "references", "usages", "callers", "who calls", "find usage", "find all",
    "occurrences", "examine", "inspect", "analyze", "review", "uses",
    "where used", "find symbol", "semantic",
    "scala", "language", "code", "navigate"
  )


  // Bug #230 — usage lists are the canonical input to
  // `dispatch_workers` (one item per reference site → per-callsite
  // LLM-or-script pipeline). Surface the next tool so the agent
  // sees the natural follow-up.
  override def suggestedNextTools: List[ToolName] = List(ToolName("dispatch_workers"))

  override def executeOutput(input: LspFindReferencesInput, context: TurnContext): Task[LspFindReferencesOutput] =
    withOpenDocumentOrThrow[LspFindReferencesOutput](
      input.languageId, input.filePath, context
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
