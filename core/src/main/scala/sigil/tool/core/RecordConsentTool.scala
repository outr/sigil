package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.ToolApproval
import sigil.tool.{RefusalPayload, TextToolOutput, Tool, ToolExample, ToolName, ToolResult}
import sigil.tool.model.RecordConsentInput

case object RecordConsentTool extends Tool {
  type Input  = RecordConsentInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RecordConsentInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("record_consent")
  val description =
    """Record the user's consent decision for a consent-gated tool. Consent is REACTIVE,
      |not a courtesy: call this ONLY when a tool you tried to use was REFUSED pending consent
      |— i.e. the framework returned a Tool-result telling you the tool needs consent and to
      |call `record_consent`. Most tools are NOT consent-gated; the framework gates only those
      |flagged `requiresUserConsent` and tells you (via that refusal) when it applies.
      |
      |Do NOT call this speculatively, and NOT just because the user selected an action from
      |`respond_options` — the selection IS the authorization, and a non-gated tool runs
      |directly. Pre-consenting for a tool that isn't gated is rejected and wastes a turn.
      |
      |- `toolName` — EXACT name of the tool the refusal named. Mistyped names persist a
      |  useless record and the gate keeps refusing.
      |- `approved` — `true` clears the gate; `false` stickily declines until a fresh `true`.
      |- `reason` — optional narrative; renders in the refusal Tool-result for future agents.
      |
      |When the user declines the prompt, record `approved=false` so a later iteration doesn't
      |re-offer it.""".stripMargin

  override val examples: List[ToolExample] = List(
    ToolExample(
      "load_claude_state was refused pending consent; the user approved the prompt",
      RecordConsentInput(toolName = "load_claude_state", approved = true,
        reason = Some("user approved loading prior Claude Code session state when the gate prompted"))
    ),
    ToolExample(
      "load_claude_state was refused pending consent; the user declined the prompt",
      RecordConsentInput(toolName = "load_claude_state", approved = false,
        reason = Some("user declined the state-load prompt"))
    )
  )

  override def executeResult(input: RecordConsentInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val targetName = ToolName(input.toolName)
    ctx.sigil.findTools.byName(targetName).flatMap {
      case None =>
        // Refuse to persist `ToolApproval` for a toolName that isn't in
        // the registry. Agents that fabricate names (the wire-log case:
        // a non-existent `start_coding` invented to clear a gate that
        // didn't need clearing) used to land a useless `ToolApproval`
        // row that polluted the audit log AND silently failed the gate
        // forever (the row matches the fabricated name, not any real
        // tool). The refusal surfaces both `record_consent`'s schema +
        // example (so the agent retries this call correctly) AND the
        // closest match by name from the broader registry (so it can
        // call the intended tool on a future iteration). The
        // offered turn roster is consulted first; the framework's full
        // static catalog is the fallback because record_consent's name
        // lookup runs across every registered tool, not just the
        // turn's offered subset.
        val candidates = (ctx.turn.offeredTools.iterator ++ ctx.sigil.staticTools.iterator).toList.distinct
        Task.pure(RefusalPayload.unknownTool(
          invokedName = input.toolName,
          offered     = candidates,
          carrier     = Some(RecordConsentTool)
        ))

      case Some(tool) if !tool.requiresUserConsent =>
        // Refuse to persist an approval for a tool that doesn't require
        // consent. The agent was confusing `respond_options.options[].value`
        // strings with tool names (e.g. recorded consent for `just_do_it`,
        // a free-form option value), and the framework happily persisted
        // bogus approvals. Distinguishing this from the unknown-tool case
        // tells the agent the tool exists but the consent record is
        // pointless.
        Task.pure(RefusalPayload.schemaMismatch(
          tool = RecordConsentTool,
          rule = s"record_consent: tool '${input.toolName}' does not require user consent.",
          hint = Some(
            "Only tools with `requiresUserConsent = true` need an approval record before " +
              "dispatch. If you mistook a `respond_options.value` string for a tool name, " +
              "consume the user's selection yourself by deciding which actual tool to call " +
              "next; you don't need to record consent for the option value."
          )
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
