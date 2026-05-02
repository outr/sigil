package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.discovery.CapabilityMatch

/**
 * Result of a `find_capability` discovery call. Bug #66 — emitted in
 * place of the tool-only [[ToolResults]] so the agent receives a
 * unified view of every matching capability category (Tool, Mode,
 * Skill) ranked by relevance.
 *
 * Distinct from [[ToolResults]] (which stays in use for tool-only
 * suggestion cascades — `CreateScriptToolTool` etc. emit
 * `ToolResults` to surface follow-up tool schemas). Discovery and
 * cascade are different shapes: cascades carry full
 * [[sigil.tool.ToolSchema]]s; discovery carries name/description
 * matches across heterogeneous categories with availability hints.
 *
 * Always `MessageRole.Tool` — find_capability's whole purpose is to
 * feed discovery results back to the agent's next iteration. Born
 * `Active` so subscribers can react (e.g. UI preview as matches
 * land); the framework then broadcasts a `StateDelta` transitioning
 * to `Complete`.
 *
 * Mode-typed matches carry their `change_mode("…")` entry hint in
 * the [[sigil.tool.discovery.CapabilityStatus.RequiresSetup]] field —
 * the agent has both the discovery (this mode exists, matches your
 * keywords) AND the actionable next call (change_mode to enter)
 * without further indirection.
 */
case class CapabilityResults(matches: List[CapabilityMatch],
                             participantId: ParticipantId,
                             conversationId: Id[Conversation],
                             topicId: Id[Topic],
                             state: EventState = EventState.Active,
                             timestamp: Timestamp = Timestamp(Nowish()),
                             role: MessageRole = MessageRole.Tool,
                             override val origin: Option[Id[Event]] = None,
                             _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
}
