package sigil.tool.process

import fabric.rw.*

/** Lifecycle state of a registered subprocess. */
enum ProcessStatus derives RW {
  case Running
  case Exited(exitCode: Int)
}
