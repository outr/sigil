package sigil.tooling

import org.eclipse.lsp4j.{CreateFile, DeleteFile, RenameFile, ResourceOperation, TextEdit, WorkspaceEdit}

import java.net.URI
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/**
 * The default [[WorkspaceEditApplier]] — writes server-suggested
 * edits anywhere on disk via `java.nio.Files`. Apps that want to
 * confine the agent's edit footprint wrap or replace with
 * [[SandboxedWorkspaceEditApplier]] / a custom impl.
 */
object PermissiveWorkspaceEditApplier extends WorkspaceEditApplier {
  override def apply(edit: WorkspaceEdit): Boolean = {
    if (edit == null) return true
    val docChanges = Option(edit.getDocumentChanges)
    val changes = Option(edit.getChanges)
    try {
      docChanges match {
        case Some(list) =>
          list.asScala.foreach { either =>
            if (either.isLeft) applyTextDocumentEdit(
              either.getLeft.getTextDocument.getUri,
              // Recent lsp4j widens TextDocumentEdit.getEdits to
              // List[Either[TextEdit, SnippetTextEdit]]. Both sides
              // are TextEdit subtypes; flatten by picking whichever
              // side is set so downstream `applyTextEdits` keeps
              // operating on a homogeneous TextEdit list.
              either.getLeft.getEdits.asScala.toList.collect {
                // SnippetTextEdit isn't a TextEdit subclass — skip
                // it. Servers that emit snippets are signalling
                // tabstops we don't carry through to disk; the
                // edit's `newText` would still be applied, but the
                // tabstop information is irrelevant once we hit
                // bytes. Most servers emit plain TextEdits anyway.
                case e if e.isLeft => e.getLeft
              }
            )
            else applyResourceOperation(either.getRight)
          }
        case None =>
          changes.foreach { byUri =>
            byUri.asScala.foreach { case (uri, edits) =>
              applyTextDocumentEdit(uri, edits.asScala.toList)
            }
          }
      }
      true
    } catch {
      case _: Throwable => false
    }
  }

  private def applyTextDocumentEdit(uri: String, edits: List[TextEdit]): Unit = {
    val path = uriToPath(uri)
    val contents = if (Files.exists(path)) Files.readString(path) else ""
    val updated = WorkspaceEditApplier.applyTextEdits(contents, edits)
    Files.createDirectories(path.getParent)
    Files.writeString(path, updated, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
  }

  private def applyResourceOperation(op: ResourceOperation): Unit = op match {
    case create: CreateFile =>
      val p = uriToPath(create.getUri)
      Files.createDirectories(p.getParent)
      if (!Files.exists(p) || (create.getOptions != null && create.getOptions.getOverwrite)) {
        Files.writeString(p, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
      }
    case rename: RenameFile =>
      val from = uriToPath(rename.getOldUri)
      val to = uriToPath(rename.getNewUri)
      Files.createDirectories(to.getParent)
      Files.move(from, to)
    case del: DeleteFile =>
      val p = uriToPath(del.getUri)
      if (del.getOptions != null && del.getOptions.getRecursive && Files.isDirectory(p)) {
        deleteRecursively(p)
      } else {
        Files.deleteIfExists(p); ()
      }
  }

  private def deleteRecursively(p: Path): Unit = {
    if (Files.isDirectory(p)) {
      Files.list(p).iterator().asScala.foreach(deleteRecursively)
    }
    Files.deleteIfExists(p); ()
  }

  private def uriToPath(uri: String): Path = Paths.get(URI.create(uri))
}
