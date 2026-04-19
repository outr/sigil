package sigil.signal

import fabric.rw.*
import sigil.event.{Message, ModeChangedEvent, TitleChangedEvent, ToolInvoke}

/**
 * The framework's built-in Signal subtypes. Sigil registers these into the
 * `Signal` poly automatically at initialization; apps add their own custom
 * Event/Delta subtypes via `Sigil.signals`.
 *
 * Includes both Events (Message, ToolInvoke, ModeChangedEvent,
 * TitleChangedEvent) and Deltas (MessageDelta, ToolDelta).
 */
object CoreSignals {

  val all: List[RW[? <: Signal]] = List(
    summon[RW[Message]],
    summon[RW[ToolInvoke]],
    summon[RW[ModeChangedEvent]],
    summon[RW[TitleChangedEvent]],
    summon[RW[MessageDelta]],
    summon[RW[ToolDelta]]
  )
}
