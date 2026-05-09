package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, ToolApproval}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{RecordConsentInput, ResponseContent}

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
case object RecordConsentTool extends TypedTool[RecordConsentInput](
  name = ToolName("record_consent"),
  description =
    """Record the user's consent decision for a tool that requires user approval.
      |
      |Use this AFTER the user has answered an approval prompt — typically the agent's own
      |`respond_options` listing the action(s) to take. The framework refuses to dispatch
      |`requiresUserConsent` tools until an approval record exists; this tool writes that
      |record.
      |
      |- `toolName` — the EXACT name of the consent-gated tool (e.g. `"start_metals"`).
      |  Mistyped names persist a useless record and the gate keeps refusing.
      |- `approved` — `true` clears the gate, `false` stickily declines until a fresh `true`
      |  is recorded.
      |- `reason` — optional narrative ("user picked Metals from setup options"). Renders in
      |  the refusal Tool-result so future agents understand the decision.
      |
      |Record consent for declined options too — when the user picked Metals from a list of
      |[Metals, Claude state, Both, Neither], record `approved=true` for `start_metals` AND
      |`approved=false` for `load_claude_state` so a later agent iteration doesn't revisit
      |the declined option.""".stripMargin,
  examples = List(
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
) {
  override protected def executeTyped(input: RecordConsentInput, ctx: TurnContext): rapid.Stream[Event] = {
    val approval = ToolApproval(
      toolName       = ToolName(input.toolName),
      approved       = input.approved,
      reason         = input.reason,
      participantId  = ctx.caller,
      conversationId = ctx.conversation.id,
      topicId        = ctx.conversation.currentTopicId
    )
    // Bug #84 — emit a Tool-role confirmation Message alongside
    // the durable ToolApproval so the orchestrator's
    // function_call ↔ function_call_output pairing stays
    // intact. Without this, OpenAI's Responses API rejects the
    // next turn with "No tool output found for function call X"
    // because the agent's `record_consent` ToolInvoke has no
    // matching Tool-role result. ToolApproval alone is a
    // ControlPlaneEvent — durable but doesn't render to a
    // ToolResult frame.
    val verdict = if (input.approved) "approved" else "declined"
    val confirmationText = input.reason match {
      case Some(reason) if reason.nonEmpty =>
        s"Consent recorded: `${input.toolName}` $verdict — $reason"
      case _ =>
        s"Consent recorded: `${input.toolName}` $verdict"
    }
    val confirmation = Message(
      participantId  = ctx.caller,
      conversationId = ctx.conversation.id,
      topicId        = ctx.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(confirmationText)),
      role           = MessageRole.Tool,
      state          = EventState.Complete
    )
    rapid.Stream.emits(List[Event](approval, confirmation))
  }
}
