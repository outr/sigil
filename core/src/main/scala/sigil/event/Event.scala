package sigil.event

import fabric.rw.RW
import lightdb.doc.{Document, JsonConversion}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.{EventState, Signal}

/**
 * A durable, stateful record in the conversation log. Every Event:
 *
 *   - belongs to a single `conversationId`
 *   - belongs to a single `topicId` within that conversation (the active
 *     thread at emission time); used for search and topic-to-conversation
 *     promotion
 *   - is attributed to a `participantId` (who originated or owns it)
 *   - carries a lifecycle `state` (Active while in flight, Complete when terminal)
 *   - is persisted in RocksDB; may be mutated in-place while Active via
 *     [[sigil.signal.Delta]]s, at which point the updated state is written back
 *
 * Registration for wire polymorphism happens via [[sigil.signal.Signal]], not
 * here — Event is a category marker within the broader Signal discriminator.
 */
trait Event extends Signal with Document[Event] {
  def participantId: ParticipantId
  def conversationId: Id[Conversation]
  def topicId: Id[Topic]
  def timestamp: Timestamp
  def state: EventState

  /**
   * Conversational role this event plays in the agent ↔ provider
   * exchange. `MessageRole.Tool` marks the event as a tool's result —
   * always re-triggers the agent's self-loop and renders as
   * `role: "tool"` on the wire. `MessageRole.Standard` (default for most
   * subclasses) is everything else.
   *
   * Each concrete Event subclass declares this as a constructor
   * field so callers can pick the role at emission time (e.g. a
   * tool that wants its `Message` to be a tool result emits
   * `Message(content = ..., role = MessageRole.Tool)`). Subclasses whose
   * role is invariant (e.g. `ToolResults`) default the field to
   * the appropriate value.
   */
  def role: MessageRole

  /**
   * Hard scope rule for this event. Default
   * [[MessageVisibility.All]] — every viewer sees it. Multi-agent
   * behaviors that emit private inter-agent chatter override at
   * emission time (`Message(..., visibility = MessageVisibility.Agents)`).
   *
   * Enforced by `Sigil.canSee(signal, viewer)` at two points:
   *   - wire-level (`signalsFor(viewer)`)
   *   - prompt-building (`buildContext` filters
   *     [[sigil.conversation.ContextFrame]]s by the running agent's id).
   *
   * Trait-level default lets existing event subclasses inherit
   * `All` without per-class boilerplate; subclasses needing scope
   * declare `visibility` as a constructor field.
   */
  def visibility: MessageVisibility = MessageVisibility.All

  /**
   * Returns a copy of this event with its `state` replaced. Used by
   * [[sigil.signal.StateDelta]] to drive the universal Active → Complete
   * transition. Each concrete Event implements this by delegating to its
   * own `copy(state = state)`.
   */
  def withState(state: EventState): Event

  /**
   * The Event that caused this one, when known — forms a parent chain
   * back to a conversational root (typically a user [[Message]]). The
   * orchestrator stamps this for tool-emitted events; agent flows
   * stamp it for events emitted in response to a trigger; user-driven
   * paths (slash commands, workflows) set it at the dispatch site.
   *
   * Bug #69 — Tool-role events MUST carry an `origin` pointing to
   * their originating [[ToolInvoke]]. [[sigil.conversation.FrameBuilder]]
   * uses this to pair the event with its call_id directly, replacing
   * the older "most-recent unresolved" scan. Multiple Tool events
   * from one `executeTyped` therefore all pair to the same call;
   * `Provider.renderFrames` merges them into a single wire-level
   * `function_call_output`.
   *
   * `None` is allowed only for genuinely root events (the user's
   * first message, a scheduled job kicked off cold, etc.). A
   * `MessageRole.Tool` event with `origin = None` is a programmer
   * error — the framework throws when it sees one rather than
   * rendering a degraded "orphan" placeholder.
   */
  def origin: Option[Id[Event]] = None

  /**
   * Returns a copy of this event with its `origin` replaced. The
   * orchestrator uses this to stamp parent ids onto tool-emitted
   * events that didn't set one explicitly. Each concrete Event
   * implements via its own `copy(origin = origin)`.
   */
  def withOrigin(origin: Option[Id[Event]]): Event
}

object Event extends JsonConversion[Event] {
  import Signal.given

  implicit override def rw: RW[Event] = summon[RW[Signal]].asInstanceOf[RW[Event]]
}
