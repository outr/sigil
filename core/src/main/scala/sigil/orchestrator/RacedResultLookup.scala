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
 * Duplicate-dispatch resolution: look up the latest SETTLED invoke of
 * `(toolName, argsHash)` from this turn's durable events and return
 * its outcome plus rendered result content, so a raced re-issue can
 * inline the original result instead of re-executing. `None` when the
 * matching call is genuinely still in flight; best-effort, a lookup
 * failure degrades to the still-finishing wording.
 */
private[orchestrator] object RacedResultLookup {

  /** Sigil #420 — resolve the latest SETTLED invoke of
    * `(toolName, argsHash)` from this turn's durable events, returning
    * its outcome plus the rendered result content (the same rendering
    * the invoke's frame carries — for an overflowed result that is its
    * own head + real-file-path summary). `None` when no matching
    * invoke has settled yet — the call is genuinely still in flight.
    * Best-effort: a lookup failure degrades to the still-finishing
    * wording rather than crashing the dispatch. */
  def racedResultLookup(sigil: Sigil,
                                convId: lightdb.id.Id[Conversation],
                                toolName: String,
                                argsHash: String,
                                turnStartMs: Long): Task[Option[(ToolOutcome, String)]] =
    sigil.withDB(_.conversationEventsConsistent(convId)).map { events =>
      events.iterator
        .collect {
          case ti: ToolInvoke
            if ti.toolName.value == toolName
              && ti.timestamp.value >= turnStartMs
              && ti.state == _root_.sigil.signal.EventState.Complete
              && ti.outcome != ToolOutcome.Pending
              && ti.input.exists(i => _root_.sigil.tool.ToolInputCanonicalizer.argsHash(i) == argsHash) =>
            ti
        }
        .toList
        .sortBy(-_.timestamp.value)
        .headOption
        .map { ti =>
          val (content, _) = _root_.sigil.conversation.FrameBuilder.toolInvokePayload(ti)
          (ti.outcome, content)
        }
    }.handleError(_ => Task.pure(None))
}
