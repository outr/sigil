package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{CodeAction, Command, Position, Range}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{LspCodeActionItem, LspCodeActionResult}

case class LspCodeActionInput(languageId: String,
                              filePath: String,
                              startLine: Int,
                              startCharacter: Int,
                              endLine: Int,
                              endCharacter: Int,
                              onlyKinds: List[String] = Nil)
  extends ToolInput derives RW

/**
 * Request available code actions for a range — quick fixes,
 * refactorings, source organizers. The server returns a list; the
 * agent picks one and runs it via `lsp_apply_code_action` (which
 * looks up by index in the session's most-recent cache, no
 * serialization needed across the tool boundary).
 *
 * `onlyKinds` (optional) filters by LSP code-action kind, e.g.
 * `["quickfix"]`, `["refactor.extract"]`, `["source.organizeImports"]`
 * — defined in the spec under "CodeActionKind".
 */
final class LspCodeActionTool(val manager: LspManager) extends Tool with LspToolSupport {
  type Input = LspCodeActionInput
  type Output = LspCodeActionResult
  val io: ToolIO[LspCodeActionInput, LspCodeActionResult] = ToolIO.derived[LspCodeActionInput, LspCodeActionResult]
  override val name = ToolName("lsp_code_action")
  override val description =
    """List code actions (quick fixes / refactors) available for a range.
      |
      |`languageId` + `filePath` identify the document.
      |`startLine`/`startCharacter`/`endLine`/`endCharacter` are 0-based; the range covers
      |the selection or cursor span. For a cursor-only invocation, set start == end.
      |`onlyKinds` (optional) filters by LSP code-action kind ("quickfix", "refactor.extract",
      |"source.organizeImports", etc.).
      |Returns `{filePath, items: [{index, kind, title}]}`. The listing is cached for a
      |separate apply-by-index tool.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set(
        "lsp",
        "code action",
        "fix",
        "quickfix",
        "refactor",
        "refactoring",
        "suggestion",
        "quick fix",
        "auto fix",
        "improve",
        "extract method",
        "extract variable",
        "organize imports",
        "transform",
        "modify",
        "change"
      ),
      toolchain = Some("lsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: LspCodeActionInput, context: ToolContext): Task[LspCodeActionResult] =
    withOpenDocumentOrThrow[LspCodeActionResult](
      input.languageId,
      input.filePath,
      context
    ) { (session, uri) =>
      val range = new Range(
        new Position(input.startLine, input.startCharacter),
        new Position(input.endLine, input.endCharacter)
      )
      session.codeAction(uri, range, input.onlyKinds).map { actions =>
        LspCodeActionResult(
          filePath = input.filePath,
          items = actions.zipWithIndex.map { case (a, idx) => toItem(a, idx) }
        )
      }
    }

  private def toItem(action: LspEither[Command, CodeAction], idx: Int): LspCodeActionItem =
    if (action.isLeft) LspCodeActionItem(idx, "command", action.getLeft.getTitle)
    else {
      val ca = action.getRight
      LspCodeActionItem(idx, Option(ca.getKind).getOrElse("(unknown)"), ca.getTitle)
    }
}
