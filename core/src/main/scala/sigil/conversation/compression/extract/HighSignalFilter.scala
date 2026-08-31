package sigil.conversation.compression.extract

/**
 * Pre-filter for per-turn memory extraction. Returns `true` when a
 * turn carries enough signal to justify spending an LLM call on
 * extraction — most turns (small-talk, acknowledgements, transient
 * questions) don't, so skipping cheaply saves a lot of cost over a
 * long conversation.
 *
 * Two surfaces: the string overload judges the user message text
 * alone; the [[ExtractionTurn]] overload additionally sees the
 * agent's response and the turn's structured evidence (settled tool
 * mutations). The default turn implementation delegates to the
 * string overload, so text-only filters implement one method.
 *
 * Sigil ships [[DefaultHighSignalFilter]] (personal-assistant
 * corpora) and [[AgenticSignalFilter]] (coding / agentic corpora);
 * combine with [[HighSignalFilter.any]] or provide your own.
 */
trait HighSignalFilter {
  def isHighSignal(userMessage: String): Boolean

  def isHighSignal(turn: ExtractionTurn): Boolean = isHighSignal(turn.userMessage)
}

object HighSignalFilter {

  /**
   * Combinator — passes when ANY of `filters` passes. Use to widen a
   * corpus-specific filter without replacing it, e.g.
   * `HighSignalFilter.any(DefaultHighSignalFilter, AgenticSignalFilter)`.
   */
  def any(filters: HighSignalFilter*): HighSignalFilter = new HighSignalFilter {
    override def isHighSignal(userMessage: String): Boolean = filters.exists(_.isHighSignal(userMessage))
    override def isHighSignal(turn: ExtractionTurn): Boolean = filters.exists(_.isHighSignal(turn))
  }
}
