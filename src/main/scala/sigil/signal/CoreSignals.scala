package sigil.signal

import fabric.rw.*
import sigil.event.{AgentState, Message, ModeChange, Stop, TitleChange, ToolInvoke, ToolResults}

/**
 * The framework's built-in Signal subtypes. Sigil registers these into the
 * `Signal` poly automatically at initialization; apps add their own custom
 * Event/Delta subtypes via `Sigil.signals`.
 *
 * Includes both Events (Message, ToolInvoke, ToolResults, ModeChange,
 * TitleChange, AgentState, Stop) and Deltas (MessageDelta, ToolDelta,
 * StateDelta, AgentStateDelta).
 */
object CoreSignals {

  val all: List[RW[? <: Signal]] = List(
    summon[RW[Message]],
    summon[RW[ToolInvoke]],
    summon[RW[ToolResults]],
    summon[RW[ModeChange]],
    summon[RW[TitleChange]],
    summon[RW[AgentState]],
    summon[RW[Stop]],
    summon[RW[MessageDelta]],
    summon[RW[ToolDelta]],
    summon[RW[StateDelta]],
    summon[RW[AgentStateDelta]]
  )
}
