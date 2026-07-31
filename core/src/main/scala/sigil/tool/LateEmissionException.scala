package sigil.tool

import lightdb.id.Id
import sigil.event.Event

/**
 * Raised when a tool body (or a fiber it leaked) calls
 * [[ToolContext.emit]] after its resolution settled and the emission
 * buffer closed. The event is NOT silently dropped — the error is
 * logged and surfaced to the emitting task so the defect is visible.
 */
class LateEmissionException(toolName: ToolName, invokeId: Id[Event])
  extends IllegalStateException(
    s"Tool `${toolName.value}` emitted an event after its resolution settled (invoke ${invokeId.value}) — " +
      "the emission buffer is closed; the event was not published. Emit durable events before the tool's " +
      "resolution completes."
  )
