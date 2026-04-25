package sigil.conversation

/**
 * Outcome of [[sigil.Sigil.classifyTopicShift]]: how the proposed topic
 * for the current turn relates to the conversation's existing topic
 * stack. Drives the orchestrator's next action — emit a TopicChange of
 * the right kind, or no event at all.
 */
enum TopicShiftResult {

  /**
   * The proposed topic is the same subject as the active Topic and the
   * current label still fits. No event is emitted; the conversation's
   * topic stack is unchanged.
   */
  case NoChange

  /**
   * The proposed topic is the same subject as the active Topic, but the
   * proposed label is sharper / more specific. The orchestrator updates
   * the active Topic record's label + summary in place and emits a
   * TopicChange of kind Rename.
   */
  case Refine

  /**
   * The proposed topic is a brand-new subject not covered by the active
   * Topic or any prior on the stack. The orchestrator creates a new
   * Topic record, pushes it onto the stack, and emits a TopicChange of
   * kind Switch.
   */
  case New

  /**
   * The proposed topic is the same subject as one of the prior topics
   * on the stack (`returnTo` is the matched entry). The orchestrator
   * truncates the stack back to that entry and emits a TopicChange of
   * kind Switch.
   */
  case Return(returnTo: TopicEntry)
}
