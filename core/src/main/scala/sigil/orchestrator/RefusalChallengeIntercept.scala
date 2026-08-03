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
 * Mid-stream intercept for an atomic `respond` whose content reads as
 * a refusal. Suppresses the reply and substitutes a synthetic
 * `_refusal_challenge` invoke plus its paired Failure so the agent
 * consults the capability catalog before refusing. Loop-safe — it
 * challenges at most once per user turn.
 */
private[orchestrator] object RefusalChallengeIntercept {

  /** Bug #126 — decide whether an atomic `respond` should be
    * suppressed and replaced with a refusal-challenge diagnostic.
    *
    * Returns:
    *   - `Some(signals)` when the content reads as a refusal AND
    *     the agent didn't consult `find_capability` since the last
    *     user-authored Message AND we haven't already challenged
    *     this user turn. The signals are a synthetic
    *     `_refusal_challenge` ToolInvoke + a paired Tool-role
    *     `Failure` Message the agent reads on its next iteration.
    *   - `None` when the content isn't a refusal, when the agent
    *     DID call `find_capability` (an informed refusal is valid),
    *     or when a prior `_refusal_challenge` is already on the
    *     tail (loop-safety — challenge once, then step aside).
    *
    * Apps tune the refusal-detection itself via
    * [[sigil.Sigil.refusalDetector]] — e.g. apps where refusal is
    * a legitimate outcome plug in [[RefusalDetector.Never]] to
    * disable the intercept entirely.
    */
  def refusalChallengeOutcome(sigil: Sigil,
                                      findCapabilityAvailable: Boolean,
                                      content: String,
                                      convId: lightdb.id.Id[Conversation],
                                      caller: ParticipantId,
                                      topicId: lightdb.id.Id[Topic]): Task[Option[List[Signal]]] = {
    // Sigil #397 — the challenge's whole corrective is "call `find_capability`
    // before refusing". With discovery suppressed (ToolPolicy.ActiveOnly/None/
    // Exclusive) there is no such tool, so an informed refusal is the only
    // option — never challenge it.
    if (!findCapabilityAvailable || !sigil.refusalDetector.isRefusal(content)) Task.pure(None)
    else sigil.withDB(_.conversationEvents(convId)).map { allEvents =>
      val convEvents = allEvents
        .filter(_.conversationId == convId)
        .sortBy(_.timestamp.value)
      // "Last user message" = most recent non-agent participantId on
      // a Message event. Agent-only conversations (delegated workers
      // with no human in the chain) skip the challenge — no user
      // intent to defend against.
      val lastUserIdx = convEvents.lastIndexWhere {
        case m: Message => !m.participantId.isInstanceOf[_root_.sigil.participant.AgentParticipantId]
        case _          => false
      }
      if (lastUserIdx < 0) None
      else {
        val tail = convEvents.drop(lastUserIdx + 1)
        val discoveryAttempted = tail.exists {
          case ti: ToolInvoke if ti.toolName.value == "find_capability" => true
          case _                                                        => false
        }
        val alreadyChallenged = tail.exists {
          case ti: ToolInvoke if ti.toolName.value == Directive.RefusalChallengeName => true
          case _                                                           => false
        }
        if (discoveryAttempted || alreadyChallenged) None
        else Some(buildRefusalChallengeSignals(caller, convId, topicId))
      }
    }
  }

  /** Construct the (synthetic-invoke, Failure-message) pair the
    * orchestrator emits when [[refusalChallengeOutcome]] fires. The
    * invoke's `_refusal_challenge` name doubles as the marker
    * `refusalChallengeOutcome` walks for on subsequent iterations
    * to enforce the once-per-user-turn limit. */
  def buildRefusalChallengeSignals(caller: ParticipantId,
                                           convId: lightdb.id.Id[Conversation],
                                           topicId: lightdb.id.Id[Topic]): List[Signal] = {
    SyntheticDiagnostic(Directive.RefusalChallenge, caller, convId, topicId,
      disposition = MessageDisposition.Failure(recoverable = true))
  }
}
