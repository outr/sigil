package sigil.tool.fs

import fabric.Json
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.output.{ToolOutputNode, ToolOutputReference}

import scala.util.Try

/**
 * Extracts the file paths a prior filesystem tool's output references, so
 * `grep` / `glob` can scope a search to that set via `from`. This is the
 * filesystem shape contract for tool composition: a reference is usable as
 * a file-set restriction only when its rows decode to a path-bearing fs
 * payload — [[GlobEntry]] (`glob`) or [[GrepNode.FileMatch]] (`grep`).
 * A reference whose rows are some other shape yields no paths, and the
 * consuming tool reports a clean "no file paths" failure.
 */
object FilePathReference {

  /** Distinct file paths referenced by `rows`, in first-seen order.
    * Non-path rows (grep's per-line children, any other shape)
    * contribute nothing. */
  def extractPaths(rows: List[ToolOutputNode]): List[String] =
    rows.iterator.flatMap(r => pathOf(r.payload)).toList.distinct

  /** Resolves an optional `from` reference into the file set a
    * filesystem search should be scoped to. `None` means no restriction.
    * A reference that can't be read (reclaimed, out of scope) or whose
    * output carries no file paths fails the task with a recoverable
    * error prefixed by `toolName`, which the framework surfaces to the
    * agent as a [[sigil.tool.ToolResult.Failure]]. */
  def resolveScope(toolName: String, from: Option[String], ctx: ToolContext): Task[Option[Set[String]]] =
    from match {
      case None      => Task.pure(None)
      case Some(ref) =>
        ToolOutputReference.resolve(ctx.sigil, ctx.conversation.id, ref).flatMap {
          case Left(reason) =>
            Task.error(new IllegalArgumentException(s"$toolName from=`$ref`: $reason"))
          case Right(resolved) =>
            val paths = extractPaths(resolved.rows)
            if (paths.isEmpty)
              Task.error(new IllegalArgumentException(
                s"$toolName from=`$ref`: the referenced output contains no file paths — `from` accepts a prior grep or glob result"
              ))
            else Task.pure(Some(paths.toSet))
        }
    }

  private def pathOf(payload: Json): Option[String] =
    Try(payload.as[GlobEntry]).toOption.map(_.path)
      .orElse(Try(payload.as[GrepNode]).toOption.collect { case fm: GrepNode.FileMatch => fm.filePath })

  /** True when `candidate` names the same file as one of `referenced`.
    * Both grep and glob emit paths relative to their own base, so an
    * exact match works when the two tools share a base; the
    * separator-anchored suffix checks keep composition working when the
    * bases differ by a prefix without matching `User.scala` against
    * `OtherUser.scala`. */
  def matches(candidate: String, referenced: Set[String]): Boolean = {
    val c = normalize(candidate)
    referenced.exists { r0 =>
      val r = normalize(r0)
      r == c || c.endsWith("/" + r) || r.endsWith("/" + c)
    }
  }

  private def normalize(p: String): String = {
    val t = p.trim
    if (t.startsWith("./")) t.drop(2) else t
  }
}
