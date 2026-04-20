package sigil.tool

import sigil.Sigil
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

/**
 * Runtime context supplied to [[Tool.execute]] at the moment of invocation.
 *
 * @param sigil        the active Sigil instance. Tools consult this for
 *                     configuration (`testMode`), shared resources
 *                     (`withDB`), or anything else the app exposes on its
 *                     Sigil implementation. Per-invocation, never cached.
 * @param chain        the chain of responsibility for this tool call — the
 *                     participant that originated the request followed by each
 *                     participant that propagated it. `chain.last` is the
 *                     immediate caller (the one actually invoking the tool);
 *                     earlier entries are the authority lineage. A
 *                     `Sigil.findTools` consulted by `find_capability` uses the
 *                     whole chain to scope tool access.
 * @param conversation the conversation state at invocation time.
 *
 * Chain is runtime-only — never persisted on Events. Each invocation's caller
 * supplies it fresh.
 */
case class ToolContext(sigil: Sigil, chain: List[ParticipantId], conversation: Conversation) {

  /**
   * The participant currently invoking this tool — `chain.last`.
   */
  def caller: ParticipantId = chain.last
}
