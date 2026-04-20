package sigil

import sigil.conversation.{Conversation, ConversationContext}
import sigil.participant.ParticipantId

/**
 * Runtime context supplied at the boundary of a unit of work — both
 * [[sigil.tool.Tool.execute]] and [[sigil.participant.Participant.process]]
 * receive one. Identifies the active Sigil, the chain-of-responsibility, the
 * conversation being acted upon, and the curated context the caller should
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
 * @param conversationContext the curated view of the conversation (events,
 *                            memories, summaries, participant context). The
 *                            caller is responsible for building this —
 *                            typically by loading from DB and running
 *                            `Sigil.curate` — before handing it to the
 *                            participant or tool.
 *
 * Chain is runtime-only — never persisted on Events. Each invocation's caller
 * supplies it fresh.
 */
case class TurnContext(sigil: Sigil,
                       chain: List[ParticipantId],
                       conversation: Conversation,
                       conversationContext: ConversationContext = ConversationContext()) {

  /**
   * The participant currently acting — `chain.last`.
   */
  def caller: ParticipantId = chain.last
}
