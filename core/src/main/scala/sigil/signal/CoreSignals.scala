package sigil.signal

import fabric.rw.*
import sigil.event.{AgentState, Message, ModeChange, Stop, TopicChange, ToolInvoke, ToolResults}

/**
 * The framework's built-in Signal subtypes. Sigil registers these into the
 * `Signal` poly automatically at initialization; apps add their own custom
 * Event / Delta / Notice subtypes via `Sigil.signalRegistrations`.
 *
 * Includes:
 *   - Events: Message, ToolInvoke, ToolResults, ModeChange, TopicChange,
 *     AgentState, Stop
 *   - Deltas: MessageDelta, ToolDelta, StateDelta, AgentStateDelta,
 *     LocationDelta, ImageDelta
 *   - Notices: RequestConversationList, ConversationListSnapshot,
 *     ConversationCreated, ConversationDeleted, SwitchConversation,
 *     ConversationSnapshot
 */
object CoreSignals {

  val all: List[RW[? <: Signal]] = List(
    summon[RW[Message]],
    summon[RW[ToolInvoke]],
    summon[RW[ToolResults]],
    summon[RW[ModeChange]],
    summon[RW[TopicChange]],
    summon[RW[AgentState]],
    summon[RW[Stop]],
    summon[RW[MessageDelta]],
    summon[RW[ToolDelta]],
    summon[RW[StateDelta]],
    summon[RW[AgentStateDelta]],
    summon[RW[LocationDelta]],
    summon[RW[ImageDelta]],
    summon[RW[RequestConversationList]],
    summon[RW[ConversationListSnapshot]],
    summon[RW[ConversationCreated]],
    summon[RW[ConversationDeleted]],
    summon[RW[SwitchConversation]],
    summon[RW[ConversationSnapshot]]
  )
}
