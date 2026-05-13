package sigil.tool.model

import fabric.rw.*

/**
 * Agent-side disposition flag on [[RespondInput]]. Determines which
 * [[sigil.event.MessageDisposition]] the framework stamps on the
 * resulting [[sigil.event.Message]] — `Success` for normal replies,
 * `Failure` when the agent has decided it can't complete the task.
 *
 * The agent surface is intentionally two values. Framework-side
 * fields on `MessageDisposition.Failure` (`recoverable`,
 * `errorContext`) are populated by the orchestrator when wrapping
 * caught exceptions, not by the agent — when an agent emits
 * `disposition = Failure`, the resulting Message has
 * `recoverable = false` and `errorContext = None`.
 */
enum ResponseDisposition derives RW {
  case Success
  case Failure
}
