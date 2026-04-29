package sigil.tooling

import org.eclipse.lsp4j.{CreateFile, DeleteFile, RenameFile, WorkspaceEdit}

import java.net.URI
import java.nio.file.{Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Applier that rejects edits referencing any path outside `root`.
 * Wraps [[PermissiveWorkspaceEditApplier]] for the actual filesystem
 * mutation; this layer's only job is the predicate check.
 *
 * Use when an LSP server is confined to a specific project — agents
 * shouldn't be able to coax it into rewriting `/etc/hosts` via a
 * crafted code-action that returns an edit with an out-of-scope URI.
 */
final class SandboxedWorkspaceEditApplier(root: Path) extends WorkspaceEditApplier {
  private val absoluteRoot: Path = root.toAbsolutePath.normalize()

  override def apply(edit: WorkspaceEdit): Boolean = {
    if (!underRoot(edit)) return false
    PermissiveWorkspaceEditApplier.apply(edit)
  }

  private def underRoot(edit: WorkspaceEdit): Boolean = {
    val uris: List[String] = {
      val fromChanges = Option(edit.getChanges).map(_.keySet().asScala.toList).getOrElse(Nil)
      val fromDocChanges = Option(edit.getDocumentChanges).map(_.asScala.toList.flatMap { e =>
        if (e.isLeft) List(e.getLeft.getTextDocument.getUri)
        else e.getRight match {
          case c: CreateFile => List(c.getUri)
          case r: RenameFile => List(r.getOldUri, r.getNewUri)
          case d: DeleteFile => List(d.getUri)
        }
      }).getOrElse(Nil)
      fromChanges ++ fromDocChanges
    }
    uris.forall { u =>
      val p = Paths.get(URI.create(u)).toAbsolutePath.normalize()
      p.startsWith(absoluteRoot)
    }
  }
}
