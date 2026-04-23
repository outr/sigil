package sigil.tool.model

import fabric.rw.*
import sigil.participant.ParticipantId
import sigil.tool.ToolInput

/**
 * Input for the `stop` tool — halt the current turn for a target agent
 * (or every agent in the conversation when `targetParticipantId = None`).
 *
 * `force` defaults to `false` (graceful): no new iterations start after
 * the current one finishes. Set to `true` to interrupt the in-flight
 * streaming provider call immediately — intended for the monitor-agent
 * pattern where a peer is about to take a destructive or off-path
 * action.
 */
case class StopInput(targetParticipantId: Option[ParticipantId] = None,
                     force: Boolean = false,
                     reason: Option[String] = None)
  extends ToolInput derives RW
