package sigil.tool.model

import fabric.rw.*

/** The named section of a turn's context a [[ContextSectionBreakdown]] measures. */
enum ContextSectionKind derives RW {

  /** The conversation's context frames. */
  case Frames

  /** Pinned (critical) memories rendered every turn. */
  case CriticalMemories

  /** Active skills aggregated across mode / role / participant. */
  case ActiveSkills

  /** The current-mode block in the system prompt. */
  case ModeBlock
}
