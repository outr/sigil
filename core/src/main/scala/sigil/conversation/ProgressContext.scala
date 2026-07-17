package sigil.conversation

/**
 * Context passed to the progress-checkpoint reflection prompt: the
 * user's substantive request, and a compact summary of the agent's
 * activity in the CURRENT inter-checkpoint window.
 *
 * The window scoping is load-bearing: the reflector judges "did this
 * window advance the task?", so its evidence must be what happened
 * since the prior checkpoint. A whole-arc history goes static once
 * it exceeds the line cap — every subsequent checkpoint then sees
 * identical input, produces an identical status, and the honesty
 * rule ("identical status = no progress") converts a healthy long
 * turn into a guaranteed no-progress streak and a forced kill.
 *
 *   - `userTask` — the substantive objective: the most-recent user
 *     [[sigil.event.Message]] (Standard role, non-agent) that ISN'T a
 *     bare continuation (#320). `None` for fresh conversations.
 *   - `latestDirective` — the latest user message when it's a distinct
 *     continuation (`"proceed"`, `"yes"`, …) the agent is acting on, so
 *     the reflection judges progress on `userTask` while still seeing
 *     what was just asked. `None` when the latest message IS the task.
 *   - `toolHistory` — at most 20 lines covering the newest calls in the
 *     window since the prior checkpoint, chronological. Each line is
 *     `"<toolName> → OK / FAIL / (no result yet)"`; respond calls
 *     additionally carry their `endsTurn` framing (a mid-task status
 *     update is labeled as such so the reflector can't read it as the
 *     final reply).
 *   - `earlierCalls` — how many calls since the objective are NOT shown
 *     in `toolHistory` (prior windows + anything beyond the line cap).
 *     Rendered so the reflector knows the window sits inside a longer
 *     arc.
 *   - `windowMutations` — count of successfully-settled invocations of
 *     `destructive`-annotated tools in the window (user-visible
 *     terminal tools like the respond family excluded — a status
 *     pulse is not external work): objective, mechanical evidence
 *     that external state changed. The checkpoint machinery treats a
 *     non-zero count as meaningful progress regardless of the
 *     reflector's self-assessment — UNLESS the mutations are
 *     same-target churn (see `windowMutationTargets`); the objective
 *     StallDetector retains veto authority over everything.
 *   - `windowMutationTargets` — the known targets (via
 *     `Tool.mutationTarget`, e.g. file paths) of the window's
 *     successful mutations. The checkpoint compares consecutive
 *     windows: mutations touching only already-seen targets with no
 *     verification anywhere between them is churn, not progress.
 *     Unknown-target mutations (`bash`) don't appear here and never
 *     contribute to a churn verdict.
 *   - `windowVerified` — whether a successfully-settled
 *     `verification`-annotated call (compile, test, diagnostics)
 *     landed in the window. Verification resets the churn chain:
 *     fix → compile → fix-again is legitimate iteration.
 */
final case class ProgressContext(userTask: Option[String],
                                 toolHistory: List[String],
                                 latestDirective: Option[String] = None,
                                 earlierCalls: Int = 0,
                                 windowMutations: Int = 0,
                                 windowMutationTargets: Set[String] = Set.empty,
                                 windowVerified: Boolean = false)
