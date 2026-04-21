package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, ModeChange}
import sigil.provider.Mode
import sigil.signal.EventState
import sigil.tool.Tool
import sigil.tool.model.ChangeModeInput

/**
 * Allows the model to transition between operating modes mid-conversation.
 *
 * Emits a `ModeChange` that orchestrators use to update the
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

  override def execute(input: ChangeModeInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(
      List(
        ModeChange(
          mode = input.mode,
          reason = input.reason,
          participantId = context.caller,
          conversationId = context.conversation.id,
          state = EventState.Complete
        )
      )
    )
}
