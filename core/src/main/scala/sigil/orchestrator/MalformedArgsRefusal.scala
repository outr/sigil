package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation, Topic, TopicShiftResult}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, Reasoning, TopicChange, TopicChangeKind, ToolInvoke, ToolOutcome}
import sigil.governor.{OutcomeVerdict, TurnOutcome}
import sigil.participant.ParticipantId
import sigil.provider.{CallId, ConversationRequest, Provider, ProviderEvent, ProviderImage, SchemaDialect, StopReason, XmlToolCallSanitizer}
import sigil.storage.StoredFileCategory
import sigil.signal.{MessageContentDelta, ContentKind, EventState, ImageDelta, MessageDelta, Signal, StateDelta, ThinkingChunk, ToolDelta, XmlToolCallLeak}
import sigil.tool.core.{CoreTools, FindCapabilityInput, RespondFamilyTool, UnknownTool}
import sigil.tool.model.{MarkdownContentParser, RespondInput, ResponseContent}
import sigil.tool.ToolName
import sigil.TurnContext
import sigil.tool.{CachedToolRead, DecodeError, DecodedCall, Freshness, GateContext, JsonInput, RefusalPayload, Tool, ToolExecutor, ToolInput, ToolRoster, WireCall}

/**
 * Renders the enriched refusal for a [[sigil.tool.WireCall.Malformed]]
 * — the resolved tool's args failed to parse or decode. Verbosity
 * follows the running model's
 * [[sigil.provider.ModelProfile.toolCallReliability]].
 */
private[orchestrator] object MalformedArgsRefusal {

  /** Render the enriched refusal for a [[WireCall.Malformed]] — the
    * resolved tool's args failed to parse or decode. Verbosity follows
    * the running model's
    * [[sigil.provider.ModelProfile.toolCallReliability]]: a solid
    * emitter reads the violated rule alone, a wobbly one gets the
    * schema + worked example pinned alongside it. */
  def malformedArgsRefusal(sigil: Sigil,
                                   request: ConversationRequest,
                                   roster: ToolRoster,
                                   name: String,
                                   error: DecodeError,
                                   rawArgs: fabric.Json,
                                   dialect: SchemaDialect): String =
    RefusalPayload.malformedArgs(roster.resolve(name), name, error, rawArgs, dialect,
      sigil.modelProfileFor(request.model).toolCallReliability)
}
