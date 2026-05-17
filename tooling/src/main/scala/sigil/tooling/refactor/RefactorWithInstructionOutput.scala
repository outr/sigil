package sigil.tooling.refactor

import fabric.rw.*

/**
 * Output for [[RefactorWithInstructionTool]] — aggregate per-file
 * report of what the workers decided and whether the edits were
 * committed.
 */
case class RefactorWithInstructionOutput(filesConsidered: Int,
                                         filesModified: Int,
                                         totalEdits: Int,
                                         perFile: List[FileRefactorReport],
                                         appliedAsWorkspaceEdit: Boolean,
                                         /** Surface-level error when the refactor was
                                           * aborted entirely (e.g. `maxWorkers` exceeded).
                                           * When set, no workers ran. */
                                         abortReason: Option[String] = None) derives RW

/** Per-file outcome: the worker's decisions + the resulting diff
  * (unified, with `(diff omitted)` placeholder when the file went
  * through unchanged). */
case class FileRefactorReport(path: String,
                              workerDecisions: List[MatchDecision],
                              workerError: Option[String] = None,
                              writeOutcome: Option[String] = None,
                              appliedDiff: Option[String] = None) derives RW
