package sigil.signal

import fabric.rw.*

/**
 * The agent's current activity state. Carried on [[sigil.event.AgentState]]
 * events and mutated via [[AgentStateDelta]] as an agent's work progresses.
 *
 *   - `Thinking` — the agent is processing but not yet producing content
 *     (LLM is reasoning, waiting on a tool result, etc.). UI typically shows
 *     a spinner or "…" indicator.
 *   - `Typing`   — the agent is currently streaming content. UI typically
 *     shows a streaming cursor or animates incoming text.
 *   - `Idle`     — the agent has finished its turn. UI suppresses any
 *     activity indicator and re-enables user input. An AgentState reaching
 *     Idle is the terminal state for the turn.
 */
enum AgentActivity derives RW {
  case Thinking, Typing, Idle
}
