package sigil.event

import fabric.rw.*
import lightdb.id.Id

/**
 * Structured discriminator on a [[Message]] indicating the message
 * originated as a `respond_options` selection — the user clicked
 * one or more option chips, not typed free text. Sigil bug #73.
 *
 * Two consumers, one shape:
 *
 *   - **Agent (LLM)** — reads the surrounding `Message.content` for
 *     the framed action request (Sigil bug #72 — text shape that
 *     small models triage cleanly).
 *   - **Chat view** — checks for `optionSelection`. When set,
 *     renders a structured selection chip (different visual shape
 *     than a free-text bubble); when `None`, renders the existing
 *     plain bubble.
 *
 * `parentOptionsEventId` points at the agent's
 * `respond_options`-emitted Message so chat views can link the
 * selection back to its prompt.
 */
case class OptionSelection(parentOptionsEventId: Id[Event],
                           prompt: String,
                           selectedOptions: List[SelectedOption])
  derives RW
