package sigil.conversation

import fabric.rw.*
import spice.net.URL

/**
 * The lifecycle state of a [[ContextFrame.ToolCall]].
 *
 * Tool calls model a transaction: an [[sigil.event.ToolInvoke]] event
 * (the call) is paired with a [[sigil.event.ToolResults]] event (the
 * result). Rather than represent these as two independent frames that
 * the renderer must pair up (which led to wire-ordering bugs when
 * other events interleaved — sigil #261), the projection collapses
 * them into a single `ToolCall` whose `state` evolves:
 *
 *   - [[Active]] — the ToolInvoke has settled but its matching
 *     ToolResults hasn't arrived yet. The frame is still rendered
 *     into the wire as an `Assistant(tool_use)` message; mid-turn
 *     debug projections may surface it, but it never reaches a real
 *     provider request in this state.
 *
 *   - [[Complete]] — the ToolResults has settled. The framework's
 *     settle path looks up the matching ToolInvoke event and rewrites
 *     its inlined frame, transitioning the state to `Complete` with
 *     the result `content` and any image URLs the tool emitted. From
 *     here a single frame renders into both the `Assistant(tool_use)`
 *     wire message and the immediately-following `User(tool_result)`
 *     wire message — guaranteeing pair adjacency without a reorder
 *     pass.
 */
enum ToolCallState derives RW {
  case Active
  case Complete(content: String, images: List[URL] = Nil)
}
