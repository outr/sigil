package sigil.conversation

import fabric.rw.*
import lightdb.id.Id
import sigil.event.Event
import sigil.participant.ParticipantId

/**
 * A render-ready unit of conversation history. Frames are the chunkable,
 * provider-facing representation of events — they map 1:1 onto the wire
 * messages most chat-completion APIs expect.
 *
 * Only events in `EventState.Complete` are materialized as frames — in-flight
 * (Active) events are invisible to the view, so the view is always a
 * consistent snapshot. When an Active event transitions to Complete, the
 * corresponding frame is appended.
 *
 * `sourceEventId` points back at the originating [[Event]] so tooling can
 * correlate a frame with its durable source record (useful for inspection,
 * replay, tests).
 *
 * The role a Text frame renders at (assistant vs. user) is derived at
 * render time by the provider from `participantId` — agents rendering their
 * own Messages emit them as `assistant`; everyone else is `user`. Keeping
 * role out of the frame lets the same frame render correctly for different
 * rendering agents in multi-agent conversations.
 */
enum ContextFrame derives RW {

  /**
   * The originating [[Event]]'s id. Declared on the enum so every case
   * must provide it; each case's constructor param implicitly satisfies
   * this abstract member.
   */
  def sourceEventId: Id[Event]

  /**
   * A textual message from a participant — user input or agent output.
   */
  case Text(content: String, participantId: ParticipantId, sourceEventId: Id[Event])

  /**
   * An assistant-issued tool call. `callId` is the `ToolInvoke._id` so a
   * following [[ToolResult]] frame can pair with it by id.
   */
  case ToolCall(toolName: String, argsJson: String, callId: Id[Event], participantId: ParticipantId, sourceEventId: Id[Event])

  /**
   * The tool-side completion of a prior [[ToolCall]]. Always renders at
   * the `tool` role, paired to a `ToolCall` via `callId`.
   */
  case ToolResult(callId: Id[Event], content: String, sourceEventId: Id[Event])

  /**
   * Out-of-band framework-authored context — mode transitions, title
   * changes, etc. Renders at the `system` or `tool` role (provider's
   * choice); carries no participant attribution.
   */
  case System(content: String, sourceEventId: Id[Event])
}
