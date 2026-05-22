package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.event.ToolApproval
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}
import sigil.tool.model.RecordConsentInput

/**
 * Records the user's consent decision for a `requiresUserConsent`
 * tool. Sigil bug #83 — the framework's tool-dispatch gate
 * verifies an approval exists before running the gated tool;
 * the agent calls this tool to register the user's verdict.
 *
 * Typical flow:
 *
 *   1. User asks the agent for setup. Agent emits
 *      `respond_options(prompt: "Pick what to set up:",
 *      options: [Metals, Claude state, Both, Neither])`.
 *   2. User picks "Metals" only.
 *   3. Agent records the user's decision:
 *      `record_consent("start_metals", approved=true,
 *      reason="user picked Metals from setup options")` and
 *      `record_consent("load_claude_state", approved=false,
 *      reason="user explicitly did not select Claude state")`.
 *   4. Agent calls `start_metals` — framework finds the
 *      approval, executes. Agent (or a future iteration's
 *      agent) trying `load_claude_state` is refused with the
 *      decline reason.
 *
 * One record covers the entire conversation. `approved=false`
 * is sticky — flip the decision by recording a fresh
 * `approved=true`.
 */
case object RecordConsentTool extends Tool {
  type Input  = RecordConsentInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RecordConsentInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("record_consent")
  val description =
    """Record the user's consent decision for a `requiresUserConsent` tool. Call AFTER the
      |user has answered an approval prompt (typically via a structured-options reply).
      |The framework refuses to dispatch consent-gated tools until an approval record exists.
      |
      |- `toolName` — EXACT name of the consent-gated tool. Mistyped names persist a useless
      |  record and the gate keeps refusing.
      |- `approved` — `true` clears the gate; `false` stickily declines until a fresh `true`.
      |- `reason` — optional narrative; renders in the refusal Tool-result for future agents.
      |
      |Record `approved=false` for declined options too, so a later iteration doesn't revisit
      |them.""".stripMargin

  override val examples: List[ToolExample] = List(
    ToolExample(
      "user picked Metals from setup options",
      RecordConsentInput(toolName = "start_metals", approved = true,
        reason = Some("user picked Metals from setup options"))
    ),
    ToolExample(
      "user did not select Claude state when offered",
      RecordConsentInput(toolName = "load_claude_state", approved = false,
        reason = Some("user did not select Claude state in setup options"))
    )
  )

  override def executeResult(input: RecordConsentInput, ctx: TurnContext): Task[ToolResult[TextToolOutput]] = {
    val targetName = ToolName(input.toolName)
    ctx.sigil.findTools.byName(targetName).flatMap {
      case None =>
        // Bug #160 — refuse to persist `ToolApproval` for a
        // toolName that isn't in the registry. Agents that
        // fabricate names (the wire-log case: a non-existent
        // `start_coding` invented to clear a gate that didn't
        // need clearing) used to land a useless `ToolApproval`
        // row that polluted the audit log AND silently failed
        // the gate forever (the row matches the fabricated
        // name, not any real tool). Resolve a logical Failure
        // instead so the agent reads "unknown tool" + the hint
        // to call `find_capability` first.
        Task.pure(ToolResult.failure(
          message = s"record_consent: unknown tool '${input.toolName}'.",
          hint = Some(
            "Call `find_capability` to discover the correct tool name before recording consent. " +
              "Don't fabricate names — the framework refuses to persist approvals for tools that " +
              "aren't in the registry.")
        ))

      case Some(_) =>
        // ToolApproval is the durable, ancillary effect of this tool —
        // emit it via `ctx.emit`. The tool's own result is the
        // confirmation text the framework pairs to the invoke.
        val approval = ToolApproval(
          toolName       = targetName,
          approved       = input.approved,
          reason         = input.reason,
          participantId  = ctx.caller,
          conversationId = ctx.conversation.id,
          topicId        = ctx.conversation.currentTopicId
        )
        val verdict = if (input.approved) "approved" else "declined"
        val confirmationText = input.reason match {
          case Some(reason) if reason.nonEmpty =>
            s"Consent recorded: `${input.toolName}` $verdict — $reason"
          case _ =>
            s"Consent recorded: `${input.toolName}` $verdict"
        }
        ctx.emit(approval).map(_ => ToolResult.Success(TextToolOutput(confirmationText)))
    }
  }
}
