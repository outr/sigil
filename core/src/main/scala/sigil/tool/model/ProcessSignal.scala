package sigil.tool.model

import fabric.rw.*

/**
 * The signal `process_signal` can deliver to a registered
 * subprocess.
 */
enum ProcessSignal derives RW {

  /**
   * Graceful stop — SIGTERM, wait the registry's grace period,
   * then SIGKILL if the child hasn't exited.
   */
  case Terminate

  /**
   * SIGINT-equivalent interrupt.
   */
  case Interrupt

  /**
   * Immediate SIGKILL — no grace period.
   */
  case Kill
}
