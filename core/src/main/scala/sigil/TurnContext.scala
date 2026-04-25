package sigil

import lightdb.id.Id
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.event.Event
import sigil.participant.ParticipantId

/**
 * Runtime context supplied at the boundary of a unit of work — both
 * [[sigil.tool.Tool.execute]] and [[sigil.participant.Participant.process]]
 * receive one. Identifies the active Sigil, the chain-of-responsibility, the
 * conversation being acted upon, and the curated view/input the caller should
 * use to compose provider requests or inspect prior events.
 *
 * @param sigil               the active Sigil instance. Consulted for
 *                            configuration (`testMode`), shared resources
 *                            (`withDB`), or anything else the app exposes on
 *                            its Sigil implementation. Per-invocation, never
 *                            cached.
 * @param chain               the chain of responsibility — the participant
 *                            that originated the request followed by each
 *                            participant that propagated it. `chain.last` is
 *                            the immediate caller (the participant actually
 *                            doing the work); earlier entries are the
 *                            authority lineage.
 * @param conversation        the conversation being acted upon.
 * @param conversationView    the materialized per-conversation projection
 *                            (rolling frames + participant projections).
 *                            Maintained by `Sigil.publish` and read cheaply
 *                            here as a point-lookup.
 * @param turnInput           the curator's per-turn output — memory /
 *                            summary / information selections plus any
 *                            app-supplied overlays. Wraps the view so
 *                            providers only need one object.
 * @param currentAgentStateId the id of the active [[sigil.event.AgentState]]
 *                            for the agent processing this turn, when
 *                            applicable. Set by the framework's dispatcher
 *                            so an agent can target lifecycle deltas
 *                            (Thinking → Typing transitions) at its own
 *                            AgentState without a DB lookup. `None` when no
 *                            AgentState is active (e.g., user-typed Message
 *                            being published externally).
 *
 * Chain is runtime-only — never persisted on Events. Each invocation's caller
 * supplies it fresh.
 */
case class TurnContext(sigil: Sigil,
                       chain: List[ParticipantId],
                       conversation: Conversation,
                       conversationView: ConversationView,
                       turnInput: TurnInput,
                       currentAgentStateId: Option[Id[Event]] = None) {

  /**
   * The participant currently acting — `chain.last`.
   */
  def caller: ParticipantId = chain.last
}
