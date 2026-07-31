package sigil.orchestrator

import fabric.rw.*

/** Which spend budget a [[Directive]] budget gate crossed. */
enum BudgetScope derives RW {
  case PerTurn
  case Conversation

  /** The scope word as it reads in the directive prose. */
  def label: String = this match {
    case PerTurn      => "per-turn"
    case Conversation => "conversation"
  }
}
