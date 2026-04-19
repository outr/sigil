package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp

/**
 * Emitted when the LLM updates the conversation title as part of its response.
 *
 * UI-only — the model doesn't need to see prior title changes in its context.
 */
case class TitleChangedEvent(title: String,
                             visibility: Set[EventVisibility] = Set(EventVisibility.UI),
                             timestamp: Timestamp = Timestamp(),
                             id: Id[Event] = Event.id()) extends Event derives RW
