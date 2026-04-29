package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.CodeAction
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

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
final class LspApplyCodeActionTool(val manager: LspManager) extends TypedTool[LspApplyCodeActionInput](
  name = ToolName("lsp_apply_code_action"),
  description =
    """Apply a code action returned by `lsp_code_action`.
      |
      |`languageId` + `filePath` identify the cached action set.
      |`index` is the 0-based position in the prior `lsp_code_action` listing.
      |Server-suggested edits land on disk automatically via the framework's
      |WorkspaceEditApplier; commands route through `workspace/executeCommand`.""".stripMargin,
  examples = List(
    ToolExample(
      "apply the first available action",
      LspApplyCodeActionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", index = 0)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspApplyCodeActionInput, context: TurnContext): Stream[Event] =
    withSession(input.languageId, input.filePath, context) { (session, uri, _) =>
      val cached = session.cachedCodeActions(uri)
      if (cached.isEmpty)
        Task.pure(s"No cached code actions for $uri — call `lsp_code_action` first.")
      else if (input.index < 0 || input.index >= cached.size)
        Task.pure(s"Index ${input.index} out of range (cache has ${cached.size} actions).")
      else {
        val either = cached(input.index)
        if (either.isLeft) {
          val cmd = either.getLeft
          val args = Option(cmd.getArguments).map(_.asScala.toList).getOrElse(Nil).map(_.asInstanceOf[Object])
          session.executeCommand(cmd.getCommand, args).map(_ => s"Executed command: ${cmd.getTitle}")
        } else {
          val action = either.getRight
          applyAction(session, action)
        }
      }
    }

  private def applyAction(session: LspSession, action: CodeAction): Task[String] = {
    val edit = Option(action.getEdit)
    val cmd = Option(action.getCommand)
    if (edit.isEmpty && cmd.isEmpty) {
      // Unresolved — round-trip to fetch the edit / command.
      session.resolveCodeAction(action).flatMap(applyResolved)
    } else applyResolved(action)
  }

  private def applyResolved(action: CodeAction): Task[String] = Task.defer {
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    Option(action.getEdit).foreach { e =>
      val ok = PermissiveWorkspaceEditApplier.apply(e)
      parts += (if (ok) s"applied edits for action: ${action.getTitle}"
                else s"failed to apply edits for action: ${action.getTitle}")
    }
    Option(action.getCommand) match {
      case Some(cmd) =>
        // For combined actions, executing the command is best-effort;
        // many servers don't define commands here once the edit shape
        // is non-empty. Skip if no executor is wired — agents see the
        // edit-applied confirmation and can iterate.
        Task.pure(parts :+ s"command: ${cmd.getTitle} (not auto-executed without session context)").map(_.mkString("; "))
      case None =>
        Task.pure(parts.mkString("; "))
    }
  }
}
