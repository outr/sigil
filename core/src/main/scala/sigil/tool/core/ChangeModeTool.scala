package sigil.tool.core

import sigil.{Sigil, TurnContext}
import sigil.event.{Event, ModeChange, MessageRole}
import sigil.provider.Mode
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ChangeModeInput

/**
 * Allows the model to transition between operating modes
 * mid-conversation. Emits a `ModeChange` that the framework uses to
 * update `Conversation.currentMode` and re-render the next provider
 * request.
 *
 * NOT auto-registered (single-mode apps don't need it). Apps with
 * multiple modes opt in by adding `ChangeModeTool` to their
 * `staticTools` list AND including `change_mode` on the relevant
 * agents' `toolNames`.
 *
 * [[descriptionFor]] enumerates the live set of registered modes
 * the caller can switch to, so the LLM sees the available targets
 * directly in the tool's documentation — no separate "Other modes
 * available" prompt block needed.
 */
case object ChangeModeTool extends TypedTool[ChangeModeInput](
  name = ToolName("change_mode"),
  description =
    """Switch operating mode. Call BEFORE starting a task that belongs to a different mode — e.g.
      |in conversation mode and the user asks for code → call change_mode("coding") first, then
      |code on the next turn. `mode` is the target's stable name from the available-modes list below.""".stripMargin
) {
  // ModeChange Events update Conversation.currentMode and the system
  // prompt's "Current mode" line. The verbose ToolResults pair is
  // redundant after settling — mark ephemeral so the curator elides
  // it from future turns.
  override def resultTtl: Option[Int] = Some(0)

  override protected def executeTyped(input: ChangeModeInput, context: TurnContext): rapid.Stream[Event] =
    context.sigil.modeByName(input.mode) match {
      case Some(mode) =>
        rapid.Stream.emits(List(
          ModeChange(
            mode = mode,
            reason = input.reason,
            participantId = context.caller,
            conversationId = context.conversation.id,
            topicId = context.conversation.currentTopicId,
            role = MessageRole.Tool
          )
        ))
      case None =>
        scribe.warn(s"change_mode called with unknown mode name: ${input.mode}")
        rapid.Stream.empty
    }

  /** Append the live set of switchable modes to the static
    * description so the LLM sees the available targets without a
    * separate prompt-rendering pass. */
  override def descriptionFor(mode: Mode, sigil: Sigil): String = {
    val others = sigil.availableModes.filterNot(_.name == mode.name)
    if (others.isEmpty) description
    else {
      val list = others.map(m => s"  - ${m.name} — ${m.description}").mkString("\n")
      s"$description\n\nAvailable modes (use the stable name for `mode`):\n$list"
    }
  }
}
