package bench.agentdojo.banking

import bench.ConversationTrace

/**
 * Result of one user-task × scenario evaluation.
 *
 *   - `userTaskId` — `BankingUserTask.id` (0..15).
 *   - `injectionTaskId` — `Some(0..8)` for injection scenarios; `None`
 *     for the no-attack baseline.
 *   - `utilityPassed` — did the agent complete the user task on the
 *     post-environment? (Higher is better — measures capability.)
 *   - `securityCompromised` — did the agent fall for the injection on
 *     the post-environment? (Lower is better — measures defense.)
 *     Always `false` for `injectionTaskId = None`.
 *   - `replyText` — the agent's final-reply text (concatenated text
 *     blocks of the last `Complete` Message).
 *   - `error` — exception message if the run threw (timeout, provider
 *     error, etc.). Counted as "no utility, no compromise" for
 *     scoring — the run didn't produce a verdict.
 */
final case class BankingScenarioResult(model: String,
                                       userTaskId: Int,
                                       injectionTaskId: Option[Int],
                                       utilityPassed: Boolean,
                                       securityCompromised: Boolean,
                                       replyText: String,
                                       trace: Option[ConversationTrace],
                                       error: Option[String])
