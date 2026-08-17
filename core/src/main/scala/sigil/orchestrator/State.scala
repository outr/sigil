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
 * Per-invocation accumulator for [[Orchestrator.process]]. Mutable
 * and scoped to one provider stream's closure — nothing here survives
 * between invocations. Tracks the in-flight tool calls, the open
 * Message the streaming-text path is filling, the per-turn text
 * buffers, and the per-response dedupe / cap counters the intercepts
 * read.
 */
private[orchestrator] final class State(val dialect: SchemaDialect = SchemaDialect.Identity) {
  /** Tool calls in flight, keyed by the provider's `CallId`. OpenAI
    * (and Anthropic with `parallel_tool_use: true`) interleave
    * deltas for multiple calls inside one turn; the orchestrator
    * needs to route each `ToolCallComplete` back to the matching
    * `ToolCallStart`'s invokeId rather than tracking a single
    * "active" call. Pre-fix this map was an `Option[Id[Event]]`
    * which silently dropped invokeIds when a second `Start` arrived
    * before the first `Complete`. */
  val activeCalls: scala.collection.mutable.LinkedHashMap[CallId, ActiveCall] =
    scala.collection.mutable.LinkedHashMap.empty

  /** Bug #69 — track the most recently settled ToolInvoke's id so a
    * `ProviderEvent.Error` arriving after `ToolCallComplete` (e.g. a
    * stream-level error after the tool itself succeeded) can still
    * stamp `origin` on the error Message. Without this fallback the
    * post-completion error path would emit a Tool-role event with
    * no parent, violating the framework's invariant. */
  var lastSettledInvokeId: Option[lightdb.id.Id[Event]] = None

  /** Most-recent active tool name — used by streaming-text paths
    * (ContentBlockStart/Delta) that want to route to whichever tool
    * is "currently" producing content. With parallel tool calls
    * this is the most recently started; `respond`-style streaming
    * is rarely paralleled in practice so the heuristic holds. */
  def activeToolName: Option[String] = activeCalls.lastOption.map(_._2.toolName)
  def activeToolInvokeId: Option[lightdb.id.Id[Event]] = activeCalls.lastOption.map(_._2.invokeId)
  var activeMessageId: Option[lightdb.id.Id[Event]] = None
  /** Tracks whether the in-flight [[activeMessageId]] has actually
    * been emitted as a `Message` event yet. `ThinkingDelta` reserves
    * the id ahead of any user-visible content so [[ThinkingChunk]]
    * `target` matches the eventual settled Message, but the Message
    * itself is only "born" once `ContentBlockDelta` lands real
    * content. Downstream branches that distinguish streaming-Message
    * from atomic-tool dispatch (`toolCallCompleteInner`,
    * `settleOrphanMessage`, the `Usage` fallback) read THIS flag,
    * not `activeMessageId.isDefined`. */
  var activeMessageCreated: Boolean = false

  /** Bug #55 — id of the most recently emitted user-visible Message
    * for this turn (`role != MessageRole.Tool`). Used as the fallback
    * target when [[ProviderEvent.Usage]] arrives but no streaming
    * `activeMessageId` exists — for tool-call-only models (llama.cpp
    * grammar-constrained `respond` invocations) the agent's
    * user-visible Message is built inside the tool's `executeResult`,
    * so the streaming-text path never fires. Without this fallback
    * the per-turn token usage would land nowhere and clients render
    * `usage = (0,0,0)` on the agent's bubble. */
  var lastUserVisibleMessageId: Option[lightdb.id.Id[Event]] = None
  var currentKind: Option[ContentKind] = None
  var currentArg: Option[String] = None
  /** Accumulated text for the current open content block. Flushed as a
    * `MessageContentDelta(complete = true, delta = full text)` when the block
    * closes (next ContentBlockStart or ToolCallComplete). */
  val currentBuffer: StringBuilder = new StringBuilder
  /** Accumulates every text fragment the agent produced across the
    * whole turn. Used by the per-turn memory extractor after `Done`. */
  val turnBuffer: StringBuilder = new StringBuilder
  /** Stable Message id per image-generation callId. The first
    * ImageGenerationPartial creates an Active Message; subsequent
    * partials emit `ImageDelta` updates targeting this id; the
    * `Complete` settles it via a final `ImageDelta` plus
    * `StateDelta(Complete)`. */
  var imageMessageIds: Map[String, lightdb.id.Id[Event]] = Map.empty

  /** Open-Message registry — every [[Message]] this turn emitted
    * with `state = Active`. Maintained automatically by
    * [[Orchestrator.trackOpenEvent]] as signals stream past, and
    * swept by `reconcileInflight` at turn end so no Active Message
    * outlives its turn — regardless of which kind of Message, and
    * with no per-kind wiring to forget. Scoped to Messages: tool
    * invokes have their own settle path (`settleOrphanToolInvoke`)
    * and some framework events (e.g. the refusal-challenge invoke)
    * have deliberate cross-turn lifecycles. */
  val openEvents: scala.collection.mutable.Set[lightdb.id.Id[Event]] =
    scala.collection.mutable.Set.empty

  /** Bug #75 — track whether the model emitted free-form text
    * (`ProviderEvent.TextDelta`, dispatched by providers when the
    * LLM sends `delta.content` outside of a tool call) AND whether
    * any tool call was started. Smaller / quantised models drift
    * to plain-text output despite `tool_choice: required` after
    * long contexts; pre-fix that text was silently dropped, the
    * turn settled silent, and bug-#46's placeholder fired with no
    * feedback to the model. Post-fix the orchestrator emits a
    * `MessageDisposition.Failure` diagnostic the agent's next
    * iteration reads and can self-correct from. */
  val plainTextBuffer: StringBuilder = new StringBuilder
  var sawAnyToolCall: Boolean = false

  /** Bug #87 — keys (toolName + canonical args JSON) of atomic tool
    * calls already dispatched this turn. When the model emits
    * multiple `function_call`s in one completion that share a key
    * (parallel hedging on a deterministic-failure tool, e.g. a
    * `requiresUserConsent` tool retried in parallel), the
    * duplicates are routed to a synthesized Tool-role result
    * pointing at the first dispatch instead of executing the same
    * (tool, args) N times. Wire shape stays well-formed —
    * `function_call` ↔ `function_call_output` pairing is satisfied
    * for every call_id; the underlying execution happens once. */
  val dispatchedKeys: scala.collection.mutable.Map[String, lightdb.id.Id[Event]] =
    scala.collection.mutable.Map.empty

  /** Tool-role result content keyed by the originating ToolInvoke id.
    * Populated as Tool-role Messages flow through `tracked.evalMap`
    * so a subsequent duplicate dispatch can inline the original
    * result into its own paired Tool-role Message rather than
    * pointing the agent at a `call_id` reference it can't
    * dereference from inside its prompt. */
  val dispatchedResultContent: scala.collection.mutable.Map[lightdb.id.Id[Event], Vector[ResponseContent]] =
    scala.collection.mutable.Map.empty

  /** The typed settle each dispatched call folded onto its invoke,
    * keyed by that invoke's id. A duplicate served from here settles
    * with the original's own payload, so its frame renders exactly
    * what the original's rendered — the model that re-asks reads the
    * same answer rather than a differently-shaped one. */
  val dispatchedSettle: scala.collection.mutable.Map[lightdb.id.Id[Event], _root_.sigil.tool.ToolSettlePayload] =
    scala.collection.mutable.Map.empty

  /** #369 — count of non-essential (action) tool calls dispatched in THIS
    * response. Once it reaches `Sigil.maxToolCallsPerResponse`, further
    * action calls in the same completion are refused with a corrective note
    * — a model that fired a whole discovered family when it needed the
    * rank-1 tool. The respond family / no_response / stop never count. */
  var dispatchedActionCount: Int = 0

  /** Wire `callId`s whose `ToolCallComplete` has already been
    * processed in this provider stream. Some OpenAI-compat backends
    * occasionally emit two complete chunks for the same call (parser
    * quirk on chunked streams); the duplicate must not re-dispatch
    * the tool or synthesize a phantom `ToolInvoke`. */
  val completedCallIds: scala.collection.mutable.Set[CallId] =
    scala.collection.mutable.Set.empty
}
