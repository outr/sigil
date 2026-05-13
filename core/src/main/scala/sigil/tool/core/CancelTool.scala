package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility, Stop}
import sigil.signal.EventState
import sigil.tool.model.{CancelInput, ResponseContent}
import sigil.tool.{ToolName, TypedTool}

/**
 * Cancel the current agent turn immediately — equivalent to the
 * user clicking a Stop button. Emits a single Complete [[Stop]]
 * event; the agent loop's iteration-boundary check picks it up and
 * exits without driving a next iteration.
 *
 * **This is NOT a turn-flow operation.** Do not call to:
 *   - End a normal turn — call `respond`.
 *   - Yield without a message — call `no_response`.
 *   - Pause between steps — there is no pausing; just call the
 *     next tool.
 *   - Transition between phases of work — just continue with the
 *     next action.
 *
 * Reserved for two cases:
 *   - The user explicitly halted the conversation (took over via
 *     the chat surface or pressed Stop).
 *   - The agent hit an unrecoverable failure where continuing
 *     would waste effort.
 *
 * Reason validation: when the supplied `reason` looks like a
 * transition ("starting X", "need to fetch Y", "wait for Z",
 * "next step", "then do A"), the tool refuses with a guidance
 * message instead of emitting the Stop event — the agent reads
 * the refusal and picks the correct tool on its next turn.
 *
 * Atomic in the success path: emits a single Complete [[Stop]]
 * event. In the refused path: emits a single tool-role
 * [[Message]] carrying a `Failure` block.
 */
case object CancelTool extends TypedTool[CancelInput](
  name = ToolName("cancel"),
  description =
    """Cancel the current agent turn immediately. Use ONLY when:
      |  - The user explicitly halted the conversation (took over via the chat).
      |  - You've encountered an unrecoverable failure and continuing would waste effort.
      |
      |This is NOT a turn-flow operation. Do NOT call to:
      |  - End a normal turn — call `respond`.
      |  - Yield without a message — call `no_response`.
      |  - "Pause" between steps — there is no pausing; just call the next tool.
      |  - Transition between phases — just continue with the next action.
      |
      |Omit `targetParticipantId` to cancel ALL agents. `force=true` interrupts an
      |in-flight call (use for monitor-agent intercepts).""".stripMargin,
  keywords = Set("cancel", "halt", "abort", "user-stop", "interrupt", "stop")
) {

  /** Patterns that signal the caller meant a turn-flow operation
    * (start something, fetch results, wait, advance to the next
    * step) rather than a cancellation. A legitimate cancel reason
    * is typically `"user requested halt"` / `"unrecoverable
    * failure: ..."` and doesn't trip them.
    *
    * The first element of each pair names the heuristic so the
    * refusal can tell the agent which pattern matched. */
  val transitionPatterns: List[(String, scala.util.matching.Regex)] = List(
    "start"      -> raw"(?i)\bstart(ing)?\b".r,
    "need-to"    -> raw"(?i)\bneed to (read|fetch|get|run|check|see|look at|review|examine|process|analyze)\b".r,
    "wait"       -> raw"(?i)\b(wait|waiting|pause|pausing)\b".r,
    "next"       -> raw"(?i)\bnext (step|action|phase|tool|call|move|iteration|turn)\b".r,
    "then"       -> raw"(?i)\bthen (i|we|the agent) (will|should|can|need|must)\b".r,
    "transition" -> raw"(?i)\btransition(ing)?\b".r,
    "yield"      -> raw"(?i)\byield(ing)?\b".r,
    "checkpoint" -> raw"(?i)\bcheckpoint\b".r
  )

  /** Returns the matching pattern's name when a transition shape is
    * detected; `None` when the reason looks like a legitimate
    * cancel. Public so apps that wrap the cancel surface (custom
    * tool, server-side validator, etc.) can reuse the heuristic. */
  def detectTransition(reason: String): Option[String] =
    if (reason.isEmpty) None
    else transitionPatterns.collectFirst {
      case (name, pat) if pat.findFirstIn(reason).isDefined => name
    }

  override protected def executeTyped(input: CancelInput, context: TurnContext): rapid.Stream[Event] = {
    val reasonText = input.reason.getOrElse("")
    detectTransition(reasonText) match {
      case Some(matchedPattern) =>
        rapid.Stream.emit[Event](refuse(input, matchedPattern, context))
      case None =>
        rapid.Stream.emit[Event](Stop(
          participantId       = context.caller,
          conversationId      = context.conversation.id,
          topicId             = context.conversation.currentTopicId,
          targetParticipantId = input.targetParticipantId,
          force               = input.force,
          reason              = input.reason,
          role                = MessageRole.Tool
        ))
    }
  }

  private def refuse(input: CancelInput, matchedPattern: String, context: TurnContext): Message = {
    val reason = input.reason.getOrElse("")
    val guidance =
      s"cancel refused: reason '$reason' reads as a transition or wait " +
        s"(matched pattern '$matchedPattern'), not a cancellation.\n\n" +
        "`cancel` is for halting the turn — call it only when the user has explicitly stopped you, " +
        "or when you've hit an unrecoverable failure.\n\n" +
        "If you have something to say, call `respond`. If you have nothing to say but you're done, " +
        "call `no_response`. If you meant to do something next (start a tool, fetch results, advance " +
        "a step), just call that tool directly — there is no pause between tool calls.\n\n" +
        "If you genuinely meant to cancel, rephrase the reason (e.g. 'user requested halt', " +
        "'unrecoverable failure: ...')."
    Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(guidance)),
      disposition    = sigil.event.MessageDisposition.Failure(recoverable = true),
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      visibility     = MessageVisibility.Agents
    )
  }
}
