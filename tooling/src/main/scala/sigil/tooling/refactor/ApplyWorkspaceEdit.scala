package sigil.tooling.refactor

import fabric.rw.*
import rapid.Task
import sigil.tool.fs.FileSystemContext

/**
 * Framework-internal primitive — NOT a `Tool`. Used by
 * [[RefactorWithInstructionTool]] and [[LspRenameSymbolTool]] to
 * commit a multi-file edit set atomically.
 *
 * **Atomicity guarantee.** The applier pre-flights every target
 * file (path readable) and snapshots their current contents BEFORE
 * writing anything. If any pre-flight check fails, no writes
 * happen and per-file errors are reported. If a write fails
 * mid-batch after pre-flight passed (transient I/O, race with
 * another writer, etc.), every already-written file is restored
 * from its snapshot and every file in the attempted set is
 * reported as `WriteRolledBack`.
 */
object ApplyWorkspaceEdit {

  final case class FileEdit(filePath: String, newContent: String)

  enum FileResult derives RW {
    case Applied(filePath: String)
    case PreflightFailed(filePath: String, reason: String)
    case WriteRolledBack(filePath: String, reason: String)
  }

  final case class ApplyResult(filesAttempted: Int,
                               filesWritten: Int,
                               results: List[FileResult]) derives RW

  /** Commit `edits` atomically. The returned task never throws —
    * every error is captured in the per-file `FileResult`. */
  def apply(fs: FileSystemContext, edits: List[FileEdit]): Task[ApplyResult] =
    if (edits.isEmpty) Task.pure(ApplyResult(filesAttempted = 0, filesWritten = 0, results = Nil))
    else applyNonEmpty(fs, edits)

  private def applyNonEmpty(fs: FileSystemContext, edits: List[FileEdit]): Task[ApplyResult] = Task.defer {
    val preflightTasks: List[Task[Either[FileResult.PreflightFailed, (FileEdit, String)]]] =
      edits.map { e =>
        fs.readFile(e.filePath).map(snap => Right((e, snap))).handleError { t =>
          Task.pure(Left(FileResult.PreflightFailed(
            e.filePath,
            s"read failed: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}"
          )))
        }
      }

    Task.sequence(preflightTasks).flatMap { preflights =>
      val (failures, ok) = preflights.partitionMap(identity)
      if (failures.nonEmpty) {
        val okPathsAsSkipped: List[FileResult] = ok.map { case (e, _) =>
          FileResult.PreflightFailed(e.filePath, "skipped because another file failed pre-flight")
        }
        Task.pure(ApplyResult(
          filesAttempted = edits.size,
          filesWritten   = 0,
          results        = failures.map(f => f: FileResult) ++ okPathsAsSkipped
        ))
      } else {
        val snapshots: Map[String, String] = ok.map { case (e, s) => e.filePath -> s }.toMap

        def writeAll(remaining: List[(FileEdit, String)],
                     written: List[String]): Task[Either[(String, Throwable, List[String]), List[String]]] =
          remaining match {
            case Nil => Task.pure(Right(written))
            case (e, _) :: tail =>
              fs.writeFile(e.filePath, e.newContent)
                .map(_ => Right(written :+ e.filePath))
                .handleError(t => Task.pure(Left((e.filePath, t, written))))
                .flatMap {
                  case Right(updated) => writeAll(tail, updated)
                  case Left(err)      => Task.pure(Left(err))
                }
          }

        writeAll(ok, Nil).flatMap {
          case Right(written) =>
            Task.pure(ApplyResult(
              filesAttempted = edits.size,
              filesWritten   = written.size,
              results        = written.map(p => FileResult.Applied(p))
            ))
          case Left((failedPath, t, written)) =>
            // Rollback every written file from snapshot. Best-effort.
            val rollbackTasks: List[Task[(String, Option[Throwable])]] = written.map { p =>
              fs.writeFile(p, snapshots(p))
                .map(_ => (p, Option.empty[Throwable]))
                .handleError(rt => Task.pure((p, Some(rt))))
            }
            Task.sequence(rollbackTasks).map { rollbacks =>
              val baseMsg =
                s"write to $failedPath failed (${t.getClass.getSimpleName}: " +
                  s"${Option(t.getMessage).getOrElse("")}); rolled back ${written.size} prior file(s)"
              val rollbackErrByPath: Map[String, Throwable] = rollbacks.collect {
                case (p, Some(rt)) => p -> rt
              }.toMap
              val perFile: List[FileResult] = edits.map { e =>
                val path = e.filePath
                rollbackErrByPath.get(path) match {
                  case Some(rt) =>
                    FileResult.WriteRolledBack(
                      path,
                      s"$baseMsg; rollback ALSO failed for $path: " +
                        s"${rt.getClass.getSimpleName}: ${Option(rt.getMessage).getOrElse("")}"
                    )
                  case None =>
                    FileResult.WriteRolledBack(path, baseMsg)
                }
              }
              ApplyResult(filesAttempted = edits.size, filesWritten = 0, results = perFile)
            }
        }
      }
    }
  }
}
