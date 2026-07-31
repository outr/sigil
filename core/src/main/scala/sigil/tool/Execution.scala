package sigil.tool

/**
 * How the framework may schedule a tool's execution:
 *
 *   - [[Inline]] — runs to completion inside the turn (the default).
 *   - [[Detachable]] — if still running when
 *     [[sigil.Sigil.toolDetachThresholdMs]] passes, the orchestrator
 *     settles the invoke with a tracking handle, lets the turn finish,
 *     and keeps the work running as a background task. Declaring it
 *     requires a [[ProgressContract]] — how the detached run surfaces
 *     liveness — and whether the task survives a user Stop.
 */
enum Execution {
  case Inline
  case Detachable(keepRunningOnStop: Boolean, progress: ProgressContract)

  def detachable: Boolean = this match {
    case Inline => false
    case Detachable(_, _) => true
  }

  def keepsRunningOnStop: Boolean = this match {
    case Detachable(keep, _) => keep
    case Inline => false
  }
}
