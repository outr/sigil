package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation, Topic, TopicShiftResult}
import sigil.event.{
  Event, Message, MessageDisposition, MessageRole, MessageVisibility, Reasoning, TopicChange, TopicChangeKind, ToolInvoke, ToolOutcome
}
import sigil.governor.{OutcomeVerdict, TurnOutcome}
import sigil.participant.ParticipantId
import sigil.provider.{CallId, ConversationRequest, Provider, ProviderEvent, ProviderImage, SchemaDialect, StopReason, XmlToolCallSanitizer}
import sigil.storage.StoredFileCategory
import sigil.signal.{
  MessageContentDelta, ContentKind, EventState, ImageDelta, MessageDelta, Signal, StateDelta, ThinkingChunk, ToolDelta, XmlToolCallLeak
}
import sigil.tool.core.{CoreTools, FindCapabilityInput, RespondFamilyTool, UnknownTool}
import sigil.tool.model.{MarkdownContentParser, RespondInput, ResponseContent}
import sigil.tool.ToolName
import sigil.TurnContext
import sigil.tool.{
  CachedToolRead, DecodeError, DecodedCall, Freshness, GateContext, JsonInput, RefusalPayload, Tool, ToolExecutor, ToolInput, ToolRoster,
  WireCall
}

/**
 * A tool call that's been started but not yet settled. Tracks the
 * provider's `CallId` (origin of routing for parallel tool calls)
 * alongside the framework-side invokeId and the wire-level tool
 * name. Stored in a `LinkedHashMap` keyed by `CallId` so iteration
 * order is insertion order (the orchestrator's streaming-text path
 * routes ContentBlock events to the most recently started tool —
 * which is `activeCalls.lastOption.map(_._2)`).
 */
final private[orchestrator] case class ActiveCall(toolName: String, invokeId: lightdb.id.Id[Event])
