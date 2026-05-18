package sigil.tooling.refactor

import fabric.rw.*

/**
 * Per-file outcome the prepare step of
 * [[RefactorWithInstructionTool]] produces — the worker's
 * decisions plus the resulting unified diff. Drained one row per
 * file into `db.toolOutputs` so the agent pages through them via
 * the standard pagination tools.
 */
case class FileRefactorReport(path: String,
                              workerDecisions: List[MatchDecision],
                              workerError: Option[String] = None,
                              appliedDiff: Option[String] = None) derives RW
