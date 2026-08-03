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
 * Mid-stream intercept for a `find_capability` dispatch repeating a
 * query the agent already issued this user turn. Suppresses the
 * dispatch and substitutes a synthetic `_repeated_query_intercept`
 * invoke plus its paired Failure telling the agent to refine the
 * query or pick from the prior hits. Once per user turn.
 */
private[orchestrator] object RepeatedQueryIntercept {

  /** Bug #159 — decide whether a `find_capability` dispatch should be
    * suppressed because the agent already issued the same query
    * earlier in this user turn.
    *
    * Returns:
    *   - `Some(signals)` when a prior `find_capability` invoke since
    *     the last user-authored Message carries identical normalized
    *     keywords AND no `_repeated_query_intercept` marker is
    *     already on the tail. The signals are a synthetic
    *     `_repeated_query_intercept` ToolInvoke + a paired Tool-role
    *     `Failure` that tells the agent to refine the query or pick
    *     a different result from the previous hits.
    *   - `None` when no duplicate is on the tail, when the prior
    *     query had different keywords, when no prior `find_capability`
    *     exists this turn, or when a prior intercept is already
    *     present (once-per-turn limit — same shape as the refusal
    *     challenge's loop safety). */
  def repeatedQueryOutcome(sigil: Sigil,
                                   keywords: String,
                                   convId: lightdb.id.Id[Conversation],
                                   caller: ParticipantId,
                                   topicId: lightdb.id.Id[Topic]): Task[Option[List[Signal]]] = {
    val normalized = Orchestrator.normalizeQuery(keywords)
    sigil.withDB(_.conversationEvents(convId)).map { allEvents =>
      val convEvents = allEvents
        .filter(_.conversationId == convId)
        .sortBy(_.timestamp.value)
      val lastUserIdx = convEvents.lastIndexWhere {
        case m: Message => !m.participantId.isInstanceOf[_root_.sigil.participant.AgentParticipantId]
        case _          => false
      }
      if (lastUserIdx < 0) None
      else {
        val tail = convEvents.drop(lastUserIdx + 1)
        val alreadyIntercepted = tail.exists {
          case ti: ToolInvoke if ti.toolName.value == Directive.RepeatedQueryInterceptName => true
          case _                                                                  => false
        }
        if (alreadyIntercepted) None
        else {
          val priorMatch = tail.exists {
            case ti: ToolInvoke if ti.toolName.value == "find_capability" =>
              ti.input match {
                case Some(fc: FindCapabilityInput) =>
                  Orchestrator.normalizeQuery(fc.keywords) == normalized
                case _ => false
              }
            case _ => false
          }
          if (!priorMatch) None
          else Some(buildRepeatedQuerySignals(caller, convId, topicId, normalized))
        }
      }
    }
  }

  /** Construct the (synthetic-invoke, Failure-message) pair the
    * orchestrator emits when [[repeatedQueryOutcome]] fires. The
    * `_repeated_query_intercept` invoke name doubles as the marker
    * the detector walks for to enforce once-per-user-turn loop
    * safety. */
  def buildRepeatedQuerySignals(caller: ParticipantId,
                                        convId: lightdb.id.Id[Conversation],
                                        topicId: lightdb.id.Id[Topic],
                                        normalizedKeywords: String): List[Signal] = {
    SyntheticDiagnostic(Directive.RepeatedQueryIntercept(normalizedKeywords), caller, convId, topicId,
      disposition = MessageDisposition.Failure(recoverable = true))
  }
}
