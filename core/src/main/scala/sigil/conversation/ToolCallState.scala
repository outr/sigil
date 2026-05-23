package sigil.conversation

import fabric.rw.*
import spice.net.URL

/**
 * The lifecycle state of a [[ContextFrame.ToolCall]].
 *
 * A tool transaction lives on a single stateful
 * [[sigil.event.ToolInvoke]] event (Sigil #265) — the call is
 * created `Active` and settled to `Complete` by a
 * [[sigil.signal.ToolDelta]] folding the typed output and outcome
 * onto the invoke. The projection collapses the lifecycle into a
 * single `ToolCall` frame whose `state` evolves:
 *
 *   - [[Active]] — the ToolInvoke has been created but the
 *     settling delta hasn't landed yet. The frame is still rendered
 *     into the wire as an `Assistant(tool_use)` message; mid-turn
 *     debug projections may surface it, but it never reaches a real
 *     provider request in this state.
 *
 *   - [[Complete]] — the invoke has settled. The framework's settle
 *     path rewrites the invoke's inlined frame, transitioning the
 *     state to `Complete` with the result `content` and any image
 *     URLs the tool's typed output emitted. From here a single
 *     frame renders into both the `Assistant(tool_use)` wire
 *     message and the immediately-following `User(tool_result)`
 *     wire message — guaranteeing pair adjacency without a reorder
 *     pass.
 */
enum ToolCallState derives RW {
  case Active
  case Complete(content: String, images: List[URL] = Nil)
}
