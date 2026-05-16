package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.TokenUsage
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName}

/**
 * Records that a tool call happened. Created `Active` at `ToolCallStart` by
 * the orchestrator and transitioned to `Complete` at `ToolCallComplete` —
 * regardless of whether the tool is streaming-producing (like `respond`) or
 * atomic (like `change_mode`). Gives subscribers a consistent "tool is
 * running / tool finished" lifecycle for every call.
 *
 * `input` is `None` while the call is in flight (the LLM is still streaming
 * args) and populated via a [[sigil.signal.ToolDelta]] when the call
 * completes.
 *
 * `internal` flags framework-internal tool calls — currently the
 * `respond` family ([[sigil.orchestrator.Orchestrator.UserVisibleTerminalTools]]).
 * The user-facing speech for these reaches the wire as a `Message` +
 * `MessageDelta`; the chip would render the same content again. Client UIs
 * filter on `internal == true` to suppress the redundant chip. The
 * framework's own logic (silent-turn detector, persistence) treats these
 * the same as any other tool call. Bug #56.
 */
case class ToolInvoke(toolName: ToolName,
                      participantId: ParticipantId,
                      conversationId: Id[Conversation],
                      topicId: Id[Topic],
                      topicIndex: Int = 0,
                      input: Option[ToolInput] = None,
                      state: EventState = EventState.Active,
                      timestamp: Timestamp = Timestamp(Nowish()),
                      role: MessageRole = MessageRole.Standard,
                      internal: Boolean = false,
                      /** Wire-level `call_id` from the provider's response
                        * (OpenAI's `call_<hash>`, etc.) when the model itself
                        * emitted the call. Captured by the provider's
                        * SSE parser and persisted here so subsequent turns can
                        * render the function_call_output with the original
                        * id — required for OpenAI's `previous_response_id`
                        * chain to find a match (sigil bug #167 r5). `None`
                        * for synthetic / framework-emitted invokes (no
                        * upstream call_id to roundtrip). */
                      callId: Option[String] = None,
                      /** Per-call token cost. Folded by [[sigil.signal.ToolDelta]]
                        * when the provider emits its trailing
                        * [[sigil.provider.ProviderEvent.Usage]] and the turn
                        * had no user-visible Message to attribute it to
                        * (e.g. change_mode / cancel / find_capability turns).
                        * Combined with `modelId`, lets cost projection charge
                        * the conversation for tool-call-only turns. */
                      usage: TokenUsage = TokenUsage(0, 0, 0),
                      /** Live tool-author-driven summary surfaced inline on
                        * client tool-call chips. Updated through
                        * [[sigil.TurnContext.setSummary]] across the
                        * execution arc:
                        *
                        *   - at start with input-derived context
                        *     ("Searching '<pattern>' in <path>")
                        *   - mid-flight ("Searched 12 files, 7 matches so far")
                        *   - at completion ("12 matches across 31 files")
                        *
                        * Each update emits a [[sigil.signal.ToolDelta]] that
                        * folds the new value here, so the persisted invoke
                        * always carries the most recent summary. Default
                        * empty — tools that don't opt in leave it as-is and
                        * the UI falls back to the tool name alone. Sigil
                        * bug #191. */
                      summary: String = "",
                      /** Model that produced this tool call. Stamped by the
                        * orchestrator from the active `ProviderRequest`. Pairs
                        * with `usage` for cost attribution on tool-call-only
                        * turns. `None` for synthetic / framework-emitted
                        * invokes (no provider call backing them). */
                      modelId: Option[Id[Model]] = None,
                      override val origin: Option[Id[Event]] = None,
                      override val source: Option[String] = None,
                      override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                      _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[sigil.conversation.Conversation]): Event = copy(conversationId = conversationId)
}
