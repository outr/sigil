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
 *   - `userTask` — the substantive objective: the most-recent user
 *     [[sigil.event.Message]] (Standard role, non-agent) that ISN'T a
 *     bare continuation (#320). `None` for fresh conversations.
 *   - `latestDirective` — the latest user message when it's a distinct
 *     continuation (`"proceed"`, `"yes"`, …) the agent is acting on, so
 *     the reflection judges progress on `userTask` while still seeing
 *     what was just asked. `None` when the latest message IS the task.
 *   - `toolHistory` — at most 20 lines, in chronological order. Each
 *     line is either `"<toolName> → OK / no result yet"` for a
 *     ToolInvoke or `"respond × N (latest: \"...\")"` for an
 *     accumulated respond series.
 */
final case class ProgressContext(userTask: Option[String],
                                 toolHistory: List[String],
                                 latestDirective: Option[String] = None)
