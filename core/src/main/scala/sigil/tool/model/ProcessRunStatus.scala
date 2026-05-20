package sigil.tool.model

import fabric.rw.*

/** Lifecycle state of a registered subprocess as reported to the agent. */
enum ProcessRunStatus derives RW {

  /** The subprocess is still running. */
  case Running

  /** The subprocess has exited. */
  case Exited
}
