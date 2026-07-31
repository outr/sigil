package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.LspLocation

case class LspFindReferencesInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int,
                                  includeDeclaration: Boolean = true,
                                  maxResults: Int = 200)
  extends ToolInput derives RW

/**
 * Typed result for [[LspFindReferencesTool]]. `truncated = true`
 * when the server returned more locations than `maxResults`; the
 * `locations` list reflects the post-cap slice.
 */
case class LspFindReferencesOutput(locations: List[LspLocation], truncated: Boolean) extends sigil.tool.ToolOutput derives RW

/**
 * Find every usage of a symbol across the workspace. The server
 * resolves through actual symbol identity (not text match), so a
 * `Foo` reference is found regardless of imports / aliases /
 * partial qualification.
 *
 * Capped at `maxResults` to keep huge codebases from blowing the
 * agent's context. `truncated` is true when the cap fired.
 */
final class LspFindReferencesTool(val manager: LspManager) extends Tool with LspToolSupport {
  type Input = LspFindReferencesInput
  type Output = LspFindReferencesOutput
  val io: ToolIO[LspFindReferencesInput, LspFindReferencesOutput] = ToolIO.derived[LspFindReferencesInput, LspFindReferencesOutput]
  override val name = ToolName("lsp_find_references")
  override val description =
    """Find every usage of a symbol across the workspace.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point inside the symbol.
      |`includeDeclaration` (default true) — whether to include the symbol's own definition.
      |`maxResults` (default 200) — caps the response.
      |
      |Returns `{locations: [{uri, filePath, range}], truncated}`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set(
        "lsp",
        "references",
        "usages",
        "callers",
        "who calls",
        "find usage",
        "find all",
        "occurrences",
        "examine",
        "inspect",
        "analyze",
        "review",
        "uses",
        "where used",
        "find symbol",
        "semantic",
        "scala",
        "language",
        "code",
        "navigate"
      ),
      toolchain = Some("lsp"),
      suggestedNextTools = List(ToolName("dispatch_workers"))
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: LspFindReferencesInput, context: ToolContext): Task[LspFindReferencesOutput] =
    withOpenDocumentOrThrow[LspFindReferencesOutput](
      input.languageId,
      input.filePath,
      context
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
