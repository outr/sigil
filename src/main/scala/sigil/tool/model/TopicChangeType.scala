package sigil.tool.model

import fabric.rw.*

/**
 * The LLM's explicit declaration, on every `respond` call, of what the
 * intended relationship is between [[RespondInput.topic]] and the
 * conversation's current topic label.
 *
 * Carried on [[RespondInput]] — required, never optional — so the model
 * has to commit to one of three categorical outcomes. Live-LLM probing
 * showed Qwen3.5-9b handles categorical choices reliably but does not
 * calibrate numeric confidence scores; a required categorical field
 * produces 4/4 correct behavior across bootstrap / refinement / hard
 * switch / same-topic scenarios where an optional Double field produced
 * 2/4.
 *
 * Maps to [[sigil.event.TopicChangeKind]] at the orchestrator layer:
 *   - [[Change]]   → [[sigil.event.TopicChangeKind.Switch]]
 *   - [[Update]]   → [[sigil.event.TopicChangeKind.Rename]] (suppressed
 *     if the current Topic's `labelLocked == true`)
 *   - [[NoChange]] → no event emitted.
 *
 * If the LLM supplies a `topic` label identical to the current topic,
 * the orchestrator ignores the declared type and emits no event — the
 * label is the source of truth when the two disagree.
 */
enum TopicChangeType derives RW {

  /**
   * The user's subject is DIFFERENT from the current topic. The
   * orchestrator activates (or creates) a fresh [[sigil.conversation.Topic]]
   * with the supplied label. Also the correct choice when the current
   * topic is the bootstrap default label and the user has supplied any
   * real subject.
   */
  case Change

  /**
   * Same subject as the current topic, but the current label is too
   * vague or doesn't reflect the specific angle the user is asking
   * about. The orchestrator renames the current Topic in place —
   * unless its `labelLocked` flag is set, in which case no event fires.
   */
  case Update

  /**
   * The current topic label still fits. No topic event is emitted.
   * Callers pass the current label unchanged in [[RespondInput.topic]].
   */
  case NoChange
}
