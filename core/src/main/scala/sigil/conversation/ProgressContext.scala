package sigil.conversation

/**
 * Context passed to the progress-checkpoint reflection prompt: the
 * user's most recent substantive request and a compact summary of
 * the agent's tool-call / respond history since that request.
 *
 * Lets the reflection model see the task it's reflecting on and
 * the surface it's been working — without these, the reflection
 * answers "awaiting instructions" even when the user clearly
 * asked the original question 15 iterations ago.
 *
 *   - `userTask` — text of the most recent user [[sigil.event.Message]]
 *     (Standard role, non-agent participant). `None` for fresh
 *     conversations or when the events store doesn't contain a
 *     substantive user message yet.
 *   - `toolHistory` — at most 20 lines, in chronological order. Each
 *     line is either `"<toolName> → OK / no result yet"` for a
 *     ToolInvoke or `"respond × N (latest: \"...\")"` for an
 *     accumulated respond series.
 */
final case class ProgressContext(userTask: Option[String],
                                 toolHistory: List[String])
