package sigil.signal

import fabric.rw.*
import sigil.event.{AgentState, CapabilityResults, Event, Message, ModeChange, Reasoning, Stop, TopicChange, ToolInvoke, ToolResults}

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
    summon[RW[Stop]],
    summon[RW[Reasoning]],
    summon[RW[CapabilityResults]]
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
    summon[RW[ConversationCleared]],
    summon[RW[SwitchConversation]],
    summon[RW[ConversationSnapshot]],
    summon[RW[RequestConversationHistory]],
    summon[RW[ConversationHistorySnapshot]],
    summon[RW[RequestStoredFileList]],
    summon[RW[StoredFileListSnapshot]],
    summon[RW[StoredFileCreated]],
    summon[RW[StoredFileDeleted]],
    summon[RW[RequestStoredFile]],
    summon[RW[StoredFileContent]],
    summon[RW[SaveStoredFile]],
    summon[RW[RequestViewerState]],
    summon[RW[ViewerStateSnapshot]],
    summon[RW[UpdateViewerState]],
    summon[RW[DeleteViewerState]],
    summon[RW[UpdateViewerStateDelta]],
    summon[RW[ViewerStateDelta]],
    summon[RW[RequestToolList]],
    summon[RW[ToolListSnapshot]],
    summon[RW[ParticipantAdded]],
    summon[RW[ParticipantRemoved]],
    summon[RW[ParticipantUpdated]],
    summon[RW[WireRequestProfile]],
    summon[RW[PinnedMemoryBudgetWarning]]
  )

  val all: List[RW[? <: Signal]] = events ++ deltas ++ notices
}
