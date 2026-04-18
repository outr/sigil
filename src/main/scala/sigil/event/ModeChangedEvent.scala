package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.provider.Mode

/**
 * Emitted when the agent transitions to a different operating mode.
 *
 * Orchestrators inspect the latest `ModeChangedEvent` in the conversation
 * to determine the `currentMode` on the next provider request. Visible to
 * both UI (for display) and Model (so the LLM sees past transitions in
 * subsequent turns).
 */
case class ModeChangedEvent(mode: Mode,
                            reason: Option[String] = None,
                            visibility: Set[EventVisibility] = Set(EventVisibility.UI, EventVisibility.Model),
                            timestamp: Timestamp = Timestamp(),
                            id: Id[Event] = Event.id()) extends Event derives RW
