package sigil.conversation.compression.extract

/**
 * Pre-filter for per-turn memory extraction. Returns `true` when a
 * user message carries enough signal to justify spending an LLM
 * call on extraction — most turns (small-talk, acknowledgements,
 * transient questions) don't, so skipping cheaply saves a lot of
 * cost over a long conversation.
 *
 * Apps provide a concrete implementation; Sigil ships
 * [[DefaultHighSignalFilter]] as a reasonable starting point.
 */
trait HighSignalFilter {
  def isHighSignal(userMessage: String): Boolean
}
