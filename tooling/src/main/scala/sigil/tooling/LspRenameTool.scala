package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspRenameResult

import scala.jdk.CollectionConverters.*

case class LspRenameInput(languageId: String,
                          filePath: String,
                          line: Int,
                          character: Int,
                          newName: String) extends ToolInput derives RW

/**
 * Rename a symbol across the entire workspace. The server returns a
 * `WorkspaceEdit` covering every occurrence; the framework's
 * [[WorkspaceEditApplier]] writes the changes to disk and the
 * affected sessions re-sync via `didChangeWatchedFiles`.
 *
 * Higher-leverage than a project-wide regex search/replace because
 * the rename respects scope (won't rewrite a same-named field on a
 * different class, etc.). For the agent, this is the safe path to
 * symbol-level refactors.
 */
final class LspRenameTool(val manager: LspManager) extends TypedOutputTool[LspRenameInput, LspRenameResult](
  name = ToolName("lsp_rename"),
  description =
    """Rename a symbol across the workspace.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point at the symbol to rename.
      |`newName` is the replacement identifier.
      |Returns `Applied(newName, filesChanged)` / `PartialFailure(newName, filesChanged)` / `NoEdits`.""".stripMargin,
  keywords = Set("lsp", "rename", "refactor", "rename symbol", "rename across project"),
  examples = List(
    ToolExample(
      "rename a method",
      LspRenameInput(
        languageId = "scala", filePath = "/abs/path/Foo.scala",
        line = 10, character = 7, newName = "newMethodName"
      )
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspRenameInput, context: TurnContext): Task[LspRenameResult] =
    withOpenDocumentTyped[LspRenameResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.rename(uri, input.line, input.character, input.newName).flatMap {
        case None       => Task.pure(LspRenameResult.NoEdits)
        case Some(edit) =>
          val ok = PermissiveWorkspaceEditApplier.apply(edit)
          val urisChanged = (
            Option(edit.getChanges).map(_.keySet().asScala.toList).getOrElse(Nil) ++
            Option(edit.getDocumentChanges).map(_.asScala.toList.flatMap { e =>
              if (e.isLeft) List(e.getLeft.getTextDocument.getUri) else Nil
            }).getOrElse(Nil)
          ).distinct
          val notifyTask = manager.notifyFilesChanged(
            urisChanged.map { u =>
              new java.io.File(java.net.URI.create(u)).getAbsolutePath -> org.eclipse.lsp4j.FileChangeType.Changed
            }.toMap
          )
          notifyTask.map { _ =>
            if (ok) LspRenameResult.Applied(input.newName, urisChanged.size)
            else    LspRenameResult.PartialFailure(input.newName, urisChanged.size)
          }
      }
    }
}
