package sigil.tooling.refactor

import fabric.rw.*

/** Outcome of the apply step. `status` is `applied` on success,
  * `not-found` when the session id was unknown (expired, already
  * applied, or never existed), and `aborted` when the atomic write
  * rolled back because at least one target file failed pre-flight
  * or its write threw — `perFile` carries the per-file detail in
  * that case. */
case class RefactorApplyOutput(sessionId: String,
                               status: RefactorApplyStatus,
                               filesModified: Int,
                               totalEditsApplied: Int,
                               perFile: List[FileApplyResult]) derives RW

enum RefactorApplyStatus derives RW {
  case Applied, NotFound, Aborted
}

/** Per-file apply outcome surfaced to the agent. Mirrors
  * [[ApplyWorkspaceEdit.FileResult]] without the wire dependency
  * on lightdb's enum-RW handling cascading into the tool's
  * output. */
case class FileApplyResult(path: String,
                           outcome: FileApplyOutcome,
                           detail: Option[String] = None) derives RW

enum FileApplyOutcome derives RW {
  case Applied, PreflightFailed, RolledBack
}
