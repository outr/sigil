package sigil.tool

import sigil.conversation.Conversation
import sigil.event.{Event, ModeChangedEvent}
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.tool.model.ChangeModeInput

/**
 * Allows the model to transition between operating modes mid-conversation.
 *
 * Emits a `ModeChangedEvent` that orchestrators use to update the
 * `currentMode` on the next `ProviderRequest`. The tool itself does not mutate
 * any state — the conversation's event log is the source of truth.
 */
object ChangeModeTool extends Tool[ChangeModeInput] {
  override protected def uniqueName: String = "change_mode"

  override protected def description: String = {
    val modeList = Mode.values.map(m => s"- $m: ${m.description}").mkString("\n")
    s"""Switch the agent's current operating mode. Call this when the user's intent shifts to a task
       |that belongs to a different mode (e.g., moving from general conversation into coding work).
       |
       |Available modes:
       |$modeList""".stripMargin
  }

  override def execute(
    input: ChangeModeInput,
    caller: ParticipantId,
    conversation: Conversation
  ): rapid.Stream[Event] =
    rapid.Stream.emits(List(ModeChangedEvent(mode = input.mode, reason = input.reason)))
}
