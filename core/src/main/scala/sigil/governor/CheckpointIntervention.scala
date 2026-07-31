package sigil.governor

import sigil.event.Message

/** Outcome envelope for a progress checkpoint's intervention.
  * Distinguishes the two recoverable shapes the framework can hit:
  *
  *   - `askingUser = false` — stall detector trip, no-progress streak,
  *     or any other "agent should now do something different" case. The
  *     intervention text is a directive to the AGENT, published as
  *     Tool-role so the agent gets to act on the guidance.
  *   - `askingUser = true` — the reflector self-reported `shouldAskUser`.
  *     Genuine "I need user input to proceed"; in a directed worker this
  *     redirects to a supervisor handoff instead.
  *
  * `terminal = true` marks a hard stall — an identical-call streak past
  * [[sigil.Sigil.hardStallIdenticalCallLimit]] that ignored every
  * cooperative nudge — which forces terminal synthesis rather than
  * continuing the loop.
  */
private[sigil] final case class CheckpointIntervention(message: Message, askingUser: Boolean, terminal: Boolean = false)
