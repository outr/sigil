package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * A transient update to an active [[sigil.event.ToolInvoke]]. Carries the
 * parsed `input` at completion time (when the LLM has finished streaming args
 * and a concrete `ToolInput` is available) and/or a state transition. Future
 * fields (partial results, progress estimates) can extend this.
 */
case class ToolDelta(target: Id[Event],
                     conversationId: Id[Conversation],
                     input: Option[sigil.tool.ToolInput] = None,
                     state: Option[EventState] = None) extends Delta derives RW {

  /**
   * Apply this delta to a [[ToolInvoke]]. Sets `input` (the parsed args) and
   * `state` from any present fields. Returns `target` unchanged if it isn't a
   * `ToolInvoke`.
   */
  override def apply(target: Event): Event = target match {
    case t: sigil.event.ToolInvoke =>
      val nextInput = input.orElse(t.input)
      val nextState = state.getOrElse(t.state)
      t.copy(input = nextInput, state = nextState)
    case other => other
  }
}
