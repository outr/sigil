package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.CodeAction
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspApplyCodeActionResult

import scala.jdk.CollectionConverters.*

case class LspApplyCodeActionInput(languageId: String,
                                   filePath: String,
                                   index: Int) extends ToolInput derives RW

/**
 * Apply a code action selected from the most-recent
 * [[LspCodeActionTool]] result for the same file. Looks up by index
 * in the session's cache so the action object never has to be
 * serialized across the tool boundary.
 *
 * Two action shapes:
 *   - `Command` — call back through `workspace/executeCommand`. The
 *     server typically returns server-side state changes via
 *     `workspace/applyEdit`, which the framework's
 *     [[WorkspaceEditApplier]] writes to disk automatically.
 *   - `CodeAction` — may carry an inline `WorkspaceEdit` (apply
 *     directly), an inline `Command` (execute it), or both. Some
 *     servers leave the `edit` field blank until `resolveCodeAction`
 *     is called; this tool resolves on demand.
 *
 * Either way, the agent's job is "pick by index"; the wire details
 * are framework-hidden.
 */
final class LspApplyCodeActionTool(val manager: LspManager) extends TypedOutputTool[LspApplyCodeActionInput, LspApplyCodeActionResult](
  name = ToolName("lsp_apply_code_action"),
  description =
    """Apply a code action returned by `lsp_code_action`.
      |
      |`languageId` + `filePath` identify the cached action set.
      |`index` is the 0-based position in the prior `lsp_code_action` listing.
      |Returns one of `Applied` / `CommandExecuted` / `Failed` / `CacheEmpty` / `OutOfRange`.""".stripMargin,
  keywords = Set("lsp", "apply", "fix", "refactor", "code action", "execute fix"),
  examples = List(
    ToolExample(
      "apply the first available action",
      LspApplyCodeActionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", index = 0)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspApplyCodeActionInput, context: TurnContext): Task[LspApplyCodeActionResult] =
    withSessionTyped[LspApplyCodeActionResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri, _) =>
      val cached = session.cachedCodeActions(uri)
      if (cached.isEmpty) Task.pure(LspApplyCodeActionResult.CacheEmpty(uri))
      else if (input.index < 0 || input.index >= cached.size)
        Task.pure(LspApplyCodeActionResult.OutOfRange(input.index, cached.size))
      else {
        val either = cached(input.index)
        if (either.isLeft) {
          val cmd = either.getLeft
          val args = Option(cmd.getArguments).map(_.asScala.toList).getOrElse(Nil).map(_.asInstanceOf[Object])
          session.executeCommand(cmd.getCommand, args).map(_ => LspApplyCodeActionResult.CommandExecuted(cmd.getTitle))
        } else applyAction(session, either.getRight)
      }
    }

  private def applyAction(session: LspSession, action: CodeAction): Task[LspApplyCodeActionResult] = {
    val edit = Option(action.getEdit)
    val cmd = Option(action.getCommand)
    if (edit.isEmpty && cmd.isEmpty) session.resolveCodeAction(action).map(applyResolved)
    else Task.pure(applyResolved(action))
  }

  private def applyResolved(action: CodeAction): LspApplyCodeActionResult = {
    val title = action.getTitle
    Option(action.getEdit) match {
      case Some(e) =>
        val ok = PermissiveWorkspaceEditApplier.apply(e)
        if (ok) LspApplyCodeActionResult.Applied(title, s"applied edits for action: $title")
        else    LspApplyCodeActionResult.Failed(title, s"failed to apply edits for action: $title")
      case None =>
        Option(action.getCommand) match {
          case Some(cmd) => LspApplyCodeActionResult.CommandExecuted(cmd.getTitle)
          case None      => LspApplyCodeActionResult.Failed(title, "action carried neither edit nor command after resolve")
        }
    }
  }
}
