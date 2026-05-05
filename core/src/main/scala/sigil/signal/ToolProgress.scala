package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.tool.ToolName

/**
 * Mid-execution progress pulse from a long-running tool. Lets clients
 * render a live status line under the tool's `ToolInvoke` chip
 * ("Imported 500 / 7,300 events", "Compiling step 3/7") rather than
 * staring at an indeterminate spinner while the tool's
 * `executeTyped` blocks.
 *
 * Tools opt in by calling `TurnContext.reportProgress(message,
 * percent?)`; the framework stamps `currentToolInvokeId` on the
 * context before dispatching `executeTyped`, so the helper has the
 * correlation id without the tool author having to thread it through.
 *
 * `invokeId` points at the [[sigil.event.ToolInvoke]] this progress
 * pulse belongs to — clients use it to associate the status line
 * with the right chip when multiple tool calls are in flight.
 *
 * `percent` is `Some(0.0..1.0)` for tools that can compute a meaningful
 * fraction (item N of M, bytes received / total) and `None` for
 * indeterminate progress (just a status string). UI renders a thin
 * progress bar when present, otherwise just the text.
 *
 * `attribution` carries the tool's name when the framework can infer
 * it (it always can — the orchestrator dispatches by tool); apps that
 * publish from outside a tool dispatch (rare) may leave it `None`.
 *
 * As a [[Notice]], `ToolProgress` is transient — never persisted,
 * never replayed. Reconnecting clients see only progress pulses
 * emitted after they reconnect; the most recent pulse is the
 * authoritative state.
 */
case class ToolProgress(invokeId: Id[Event],
                        conversationId: Id[Conversation],
                        message: String,
                        percent: Option[Double] = None,
                        attribution: Option[ToolName] = None) extends Notice derives RW
