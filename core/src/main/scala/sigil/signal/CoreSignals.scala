package sigil.signal

import fabric.rw.*
import sigil.event.{AgentState, Event, Message, ModeChange, Stop, TopicChange, ToolInvoke, ToolResults}

/**
 * The framework's built-in Signal subtypes, split into typed sublists so
 * downstream consumers (codegen, wire transports) can enumerate "which
 * subtypes are Events vs Deltas vs Notices" without reflection or
 * Java-classfile spelunking.
 *
 * `Signal` remains the single polymorphic discriminator on the wire — every
 * subtype here registers into `Signal`'s poly RW. The split is purely
 * organizational: it lets `sigil.Sigil.eventSubtypeNames` /
 * `deltaSubtypeNames` / `noticeSubtypeNames` answer "is this subtype durable
 * (Event) or transient (Delta / Notice)?" — the question Dart codegen needs
 * to populate spice's `durableSubtypes` config knob, and the question wire
 * routers need to choose between persistent and ephemeral channels.
 *
 * Apps register their own custom Events / Deltas / Notices via
 * `Sigil.eventRegistrations`, `deltaRegistrations`, `noticeRegistrations`.
 */
object CoreSignals {

  val events: List[RW[? <: Event]] = List(
    summon[RW[Message]],
    summon[RW[ToolInvoke]],
    summon[RW[ToolResults]],
    summon[RW[ModeChange]],
    summon[RW[TopicChange]],
    summon[RW[AgentState]],
    summon[RW[Stop]]
  )

  val deltas: List[RW[? <: Delta]] = List(
    summon[RW[MessageDelta]],
    summon[RW[ToolDelta]],
    summon[RW[StateDelta]],
    summon[RW[AgentStateDelta]],
    summon[RW[LocationDelta]],
    summon[RW[ImageDelta]]
  )

  val notices: List[RW[? <: Notice]] = List(
    summon[RW[RequestConversationList]],
    summon[RW[ConversationListSnapshot]],
    summon[RW[ConversationCreated]],
    summon[RW[ConversationDeleted]],
    summon[RW[SwitchConversation]],
    summon[RW[ConversationSnapshot]],
    summon[RW[RequestStoredFileList]],
    summon[RW[StoredFileListSnapshot]],
    summon[RW[StoredFileCreated]],
    summon[RW[StoredFileDeleted]],
    summon[RW[RequestStoredFile]],
    summon[RW[StoredFileContent]],
    summon[RW[SaveStoredFile]]
  )

  val all: List[RW[? <: Signal]] = events ++ deltas ++ notices
}
