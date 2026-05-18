package sigil.tooling.refactor

import fabric.rw.*

/**
 * Outcome of the cancel step. `Cancelled` when the session was
 * present and dropped; `NotFound` when the id wasn't in the store
 * (expired, already applied, already cancelled, never existed) —
 * cancel is idempotent across every terminal state.
 */
case class RefactorCancelOutput(sessionId: String,
                                status: RefactorCancelStatus)
  derives RW

enum RefactorCancelStatus derives RW {
  case Cancelled, NotFound
}
