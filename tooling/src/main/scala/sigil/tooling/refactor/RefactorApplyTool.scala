package sigil.tooling.refactor

import rapid.Task
import sigil.TurnContext
import sigil.tool.fs.FileSystemContext
import sigil.tool.{ToolName, TypedOutputTool}

/**
 * Commit step of the three-tool refactor session. Looks up the
 * prepared session by id, hands its
 * [[ApplyWorkspaceEdit.FileEdit]] set to [[ApplyWorkspaceEdit]]
 * for the atomic write, and removes the session from the store on
 * the way out so it can't be applied twice.
 *
 * Returns `NotFound` when the session id is unknown (expired,
 * already-applied, or never existed) — `not-found` is the
 * universal "nothing to apply here" outcome so callers don't have
 * to discriminate. The structured `FileApplyResult` list surfaces
 * pre-flight + rollback detail when an atomic write rolled back.
 */
final class RefactorApplyTool(fs: FileSystemContext,
                              sessionStore: RefactorSessionStore)
  extends TypedOutputTool[RefactorApplyInput, RefactorApplyOutput](
    name = ToolName("refactor_apply"),
    description =
      """Commit the workspace edits previously staged by the prior preparation step.
        |Takes the opaque `sessionId` returned at preparation time and applies the stored edits
        |atomically — every file succeeds or none do, with full rollback if any write fails.
        |
        |Returns one of:
        |  - `applied`     — all stored edits committed; the session has been consumed.
        |  - `not-found`   — no session matched the id (expired, already applied, or never
        |                    existed). Re-run the preparation step if you still want the change.
        |  - `aborted`     — atomic pre-flight or write failed; the per-file detail reports
        |                    which file blocked the apply and why.
        |
        |After a successful apply the session no longer exists; calling apply again returns
        |`not-found`.""".stripMargin,
    keywords = Set("refactor", "apply", "commit", "session", "workspace", "edit")
  )
  with sigil.tool.DestructiveExternalTool {

  override def paginate: Boolean = false

  override protected def executeTyped(input: RefactorApplyInput,
                                      ctx: TurnContext): Task[RefactorApplyOutput] =
    sessionStore.take(input.sessionId) match {
      case None =>
        Task.pure(RefactorApplyOutput(
          sessionId = input.sessionId,
          status = RefactorApplyStatus.NotFound,
          filesModified = 0,
          totalEditsApplied = 0,
          perFile = Nil
        ))
      case Some(session) =>
        if (session.edits.isEmpty) {
          // Nothing to commit — the prepare step staged an empty
          // session (no candidate files, or every file ended up a
          // no-op). Treat as a successful apply of zero edits;
          // the caller's wire shape mirrors a normal apply.
          Task.pure(RefactorApplyOutput(
            sessionId = input.sessionId,
            status = RefactorApplyStatus.Applied,
            filesModified = 0,
            totalEditsApplied = 0,
            perFile = Nil
          ))
        } else {
          ApplyWorkspaceEdit(fs, session.edits).map { result =>
            val perFile = result.results.map(toPerFile)
            val rolledBack = result.results.exists {
              case _: ApplyWorkspaceEdit.FileResult.WriteRolledBack => true
              case _ => false
            }
            val preflightFailed = result.results.exists {
              case _: ApplyWorkspaceEdit.FileResult.PreflightFailed => true
              case _ => false
            }
            val status =
              if (result.filesWritten == session.edits.size) RefactorApplyStatus.Applied
              else if (rolledBack || preflightFailed) RefactorApplyStatus.Aborted
              else RefactorApplyStatus.Applied
            RefactorApplyOutput(
              sessionId = input.sessionId,
              status = status,
              filesModified = result.filesWritten,
              totalEditsApplied = result.filesWritten,
              perFile = perFile
            )
          }
        }
    }

  private def toPerFile(r: ApplyWorkspaceEdit.FileResult): FileApplyResult = r match {
    case ApplyWorkspaceEdit.FileResult.Applied(p) =>
      FileApplyResult(path = p, outcome = FileApplyOutcome.Applied)
    case ApplyWorkspaceEdit.FileResult.PreflightFailed(p, msg) =>
      FileApplyResult(path = p, outcome = FileApplyOutcome.PreflightFailed, detail = Some(msg))
    case ApplyWorkspaceEdit.FileResult.WriteRolledBack(p, msg) =>
      FileApplyResult(path = p, outcome = FileApplyOutcome.RolledBack, detail = Some(msg))
  }
}
