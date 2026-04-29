package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

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
final class LspRenameTool(val manager: LspManager) extends TypedTool[LspRenameInput](
  name = ToolName("lsp_rename"),
  description =
    """Rename a symbol across the workspace.
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point at the symbol to rename.
      |`newName` is the replacement identifier.
      |Server-suggested edits land on disk via the framework's WorkspaceEditApplier.""".stripMargin,
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
  override protected def executeTyped(input: LspRenameInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.rename(uri, input.line, input.character, input.newName).flatMap {
        case None       => Task.pure("Server returned no rename edits — symbol may not be renameable here.")
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
          notifyTask.map(_ =>
            if (ok) s"Renamed to '${input.newName}' across ${urisChanged.size} file(s)."
            else s"Rename produced edits across ${urisChanged.size} file(s) but at least one application failed."
          )
      }
    }
}
