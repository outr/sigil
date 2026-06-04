package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.Sigil
import sigil.tool.ToolContext
import sigil.event.{ModeChange, MessageRole}
import sigil.provider.Mode
import sigil.tool.{RefusalPayload, TextToolOutput, Tool, ToolName, ToolResult}
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
case object ChangeModeTool extends Tool {
  type Input = ChangeModeInput
  type Output = TextToolOutput
  val inputRW = summon[RW[ChangeModeInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("change_mode")
  val description =
    """Switch to a mode whose domain matches the user's current task. Each
      |listed mode below describes the work it handles (writing code, web
      |research, etc.); when the user's request lands in that domain — even
      |a one-shot ask like "write a function" or "look this up on the web"
      |— switch to that mode FIRST. The mode loads tools, instructions, and
      |behavior tuned for that work; staying in a non-matching mode forces
      |find_capability discovery on every operation.
      |
      |Skip the switch only when no listed mode's domain fits — then use
      |find_capability directly.
      |
      |`mode` is the target's stable name from the available-modes list below.
      |After change_mode succeeds, the new mode's tools are directly callable
      |on the next turn.""".stripMargin

  // Curated keyword surface for discovery ranking. Tight on what
  // `change_mode` actually does — switch the agent's operating
  // posture / toolset — without leaking into adjacent intents like
  // `pin_complexity` (tier / level / complexity / cost), which are
  // separate tools with their own keyword sets. Without these, the
  // BM25 ranker would score `change_mode` purely on its description
  // prose and accidentally match tier-shaped queries (sigil bug
  // #158).
  override val keywords: Set[String] = Set(
    "mode",
    "modes",
    "switch",
    "change",
    "transition",
    "operating",
    "posture",
    "kit",
    "toolset",
    "tools"
  )

  override val examples: List[sigil.tool.ToolExample] = List(
    sigil.tool.ToolExample(
      "switch to coding mode for a code-edit task",
      ChangeModeInput(mode = "coding", reason = Some("user wants a function written"))
    )
  )

  // ModeChange Events update Conversation.currentMode and the system
  // prompt's "Current mode" line. The verbose settled tool-call
  // frame is redundant after the mode is in effect — mark ephemeral
  // so the curator elides it from future turns.
  override def resultTtl: Option[Int] = Some(0)

  override def executeResult(input: ChangeModeInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.modeByName(input.mode) match {
      case Some(mode) if mode.name == context.conversation.currentMode.name =>
        // Refuse same-mode re-entry. The didactic phrasing names the
        // no-op AND tells the agent the blocker is elsewhere so its
        // next iteration doesn't repeat; the schema + example block is
        // appended so an agent that fumbled the call once doesn't have
        // to re-discover the shape on its retry.
        scribe.warn(s"change_mode same-mode no-op rejected: ${input.mode}")
        Task.pure(RefusalPayload.schemaMismatch(
          tool = ChangeModeTool,
          rule = s"`change_mode` rejected: conversation is already in `${mode.name}` mode.",
          hint = Some(
            "Re-entering the current mode won't change anything — the next iteration's prompt " +
              "will be identical to this one. If the agent loop appears stuck, the gap is in " +
              "your next tool choice, not the mode."
          )
        ))
      case Some(mode) =>
        context.emit(ModeChange(
          mode = mode,
          reason = input.reason,
          participantId = context.caller,
          conversationId = context.conversation.id,
          topicId = context.conversation.currentTopicId,
          role = MessageRole.Tool
        )).map(_ => ToolResult.Success(TextToolOutput(s"Switched to mode '${mode.name}'.")))
      case None =>
        scribe.warn(s"change_mode called with unknown mode name: ${input.mode}")
        val available = context.sigil.availableModes.map(_.name).mkString(", ")
        Task.pure(RefusalPayload.schemaMismatch(
          tool = ChangeModeTool,
          rule = s"`change_mode` rejected: no mode named `${input.mode}` registered.",
          hint = Some(s"Available modes: $available.")
        ))
    }

  /**
   * Append the live set of switchable modes to the static
   * description so the LLM sees the available targets without a
   * separate prompt-rendering pass.
   */
  override def descriptionFor(mode: Mode, sigil: Sigil): String = {
    val others = sigil.availableModes.filterNot(_.name == mode.name)
    if (others.isEmpty) description
    else {
      val list = others.map(m => s"  - ${m.name} — ${m.description}").mkString("\n")
      s"$description\n\nAvailable modes (use the stable name for `mode`):\n$list"
    }
  }
}
