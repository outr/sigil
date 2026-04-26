package sigil.event

import fabric.rw.*

/**
 * Conversational role an [[Event]] plays in the agent ↔ provider
 * exchange. Used by [[sigil.dispatcher.TriggerFilter]] (re-trigger
 * decisions) and [[sigil.conversation.FrameBuilder]] (wire-frame
 * selection).
 *
 *   - [[Tool]] — the event is a tool's result. Always re-triggers
 *     the agent's self-loop (so multi-step tool flows like
 *     `read_file → reason → send_money → respond` advance
 *     iteration-by-iteration). Renders to the wire as a
 *     `role: "tool"` message paired with the most-recent unresolved
 *     `ToolInvoke` via the standard pairing mechanism.
 *   - [[Standard]] — the event is *not* a tool result. Default for
 *     `Message`, `ToolInvoke`, `ModeChange`, `TopicChange`,
 *     `AgentState`, `Stop`, etc. Routing follows the existing rules:
 *     `Message` from-self isn't a re-trigger, `Message` from-other
 *     is, `ModeChange`/`TopicChange` re-trigger any participant,
 *     and `AgentState`/`Stop` are control-plane (never triggers).
 *
 * Tool authors emit `Role.Tool` events when they want their
 * structured payload to feed the agent's next iteration as a tool
 * result. Terminal tools (`respond`, `no_response`, `change_mode`,
 * `stop`) leave the default `Role.Standard` so their emissions
 * follow the assistant-text / control-event paths.
 */
enum Role derives RW {
  case Tool
  case Standard
}
