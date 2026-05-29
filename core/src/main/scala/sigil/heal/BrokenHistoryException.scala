package sigil.heal

/**
 * Typed exception thrown by [[sigil.provider.Provider.renderFrames]]
 * when the rendered frame trail carries a dangling
 * [[sigil.conversation.ContextFrame.ToolCall]] (or any other
 * framework-invariant violation that surfaces as a structured
 * [[CorruptionEvidence]]).
 *
 * Replaces the prior log-and-ship-broken-wire-anyway path. Catching
 * the typed exception in the agent loop's `handleError` lets
 * [[sigil.provider.ErrorClassifier]] dispatch directly to a matching
 * [[HealingStrategy]] without parsing the upstream error body — the
 * corruption is already structured.
 */
case class BrokenHistoryException(corruption: List[CorruptionEvidence])
  extends RuntimeException(
    s"Conversation history is broken — ${corruption.size} corruption " +
      s"evidence row(s) detected at provider-render time. " +
      s"Evidence: ${corruption.map(_.getClass.getSimpleName).mkString(", ")}."
  )
