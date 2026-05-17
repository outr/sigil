package sigil.tooling.refactor

/**
 * One prepared refactor — the worker dispatch produced a draft
 * [[ApplyWorkspaceEdit.FileEdit]] set; the user / agent now has a
 * window to inspect via pagination and either commit
 * ([[RefactorApplyTool]]) or drop ([[RefactorCancelTool]]).
 *
 *   - `sessionId` — opaque handle echoed back from prepare; the
 *     follow-up tools reference it.
 *   - `edits` — the per-file `(path, newContent)` pairs the
 *     follow-up apply hands to [[ApplyWorkspaceEdit]].
 *   - `perFile` — the rich per-file report the prepare step built
 *     (worker decisions, diff, any worker errors). Inspectable via
 *     the paginated drain rows; held here so the apply step can
 *     correlate per-file outcomes.
 *   - `createdAtMillis` — epoch millis. The session store evicts
 *     sessions whose age (against the store's clock) exceeds the
 *     configured TTL.
 */
final case class RefactorSession(sessionId: String,
                                 edits: List[ApplyWorkspaceEdit.FileEdit],
                                 perFile: List[FileRefactorReport],
                                 createdAtMillis: Long)
