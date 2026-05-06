package sigil.event

import fabric.rw.RW
import lightdb.doc.{Document, JsonConversion}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, ContextFrame, Topic}
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

  /**
   * Origin attribution for this event. Distinct from
   * [[sigil.event.Message.modelId]] (which answers "which model
   * produced the content?"). `None` (default) means the event
   * originated in this Sigil instance — a fresh provider call, a
   * direct user input, a framework-emitted lifecycle pulse.
   * `Some(<source>)` means the event was imported / replayed from
   * another agent tool, a wire-log replay, or an exported session
   * file.
   *
   * Convention (lowercase, hyphen-separated): `claude-code`,
   * `cursor`, `openhands`, `wire-log-replay`. The framework does
   * NOT enforce or interpret the value — it's an attribution string
   * the consumer (chat UI metadata strip, cost rollup gauges, audit
   * tooling) reads for rendering decisions.
   *
   * Typical pairing with [[sigil.Sigil.publishHistorical]]: bulk-import
   * paths stamp the source on every imported event so downstream UIs
   * can distinguish "current state, this Sigil paid for it" from
   * "imported context, paid for elsewhere".
   */
  def source: Option[String] = None

  /**
   * Render-ready frame for prompt construction. Computed once at
   * settle-time (`EventState.Complete`) by `FrameBuilder.computeFrame`
   * and persisted inline on the event. `None` for in-flight events
   * and event types that don't produce a frame ([[sigil.event.AgentState]],
   * [[sigil.event.Stop]], [[sigil.event.ControlPlaneEvent]]s).
   *
   * Source of truth for prompt construction — the curator queries
   * `db.events` (with `contextFrame.isDefined` filter) to materialize
   * a conversation's prompt history rather than maintaining a separate
   * frames Vector projection. Bug #26.
   */
  def contextFrame: Option[ContextFrame] = None

  /**
   * Returns a copy of this event with its `contextFrame` replaced.
   * The framework's publish pipeline calls this when an event
   * settles to `EventState.Complete` to inline the freshly-computed
   * frame onto the durable event row. Each concrete Event implements
   * via its own `copy(contextFrame = contextFrame)`.
   */
  def withContextFrame(contextFrame: Option[ContextFrame]): Event
}

object Event extends JsonConversion[Event] {
  import Signal.given

  implicit override def rw: RW[Event] = summon[RW[Signal]].asInstanceOf[RW[Event]]
}
