package sigil.conversation

import fabric.rw.*
import lightdb.time.Timestamp
import sigil.tool.ToolName

/**
 * One entry in [[ParticipantProjection.recentToolInvocations]] — the
 * persisted rolling window of a participant's tool dispatches scoped
 * to a conversation.
 *
 * Carries enough metadata to support two surfaces:
 *   - Prompt rendering — `argsPreview` lets the agent see WHAT it
 *     called (not just THAT it called a tool), so the model can
 *     reason about whether re-issuing makes sense.
 *   - Duplicate detection — `argsHash` is a canonical sorted-key
 *     SHA-256 over the tool's args, so semantically-identical calls
 *     emitted with fields in different orders still collapse to the
 *     same bucket.
 *
 * @param toolName    the dispatched tool's name.
 * @param argsHash    canonical sorted-key SHA-256 of the input — see
 *                    [[sigil.tool.ToolInputCanonicalizer.argsHash]].
 * @param argsPreview short human-readable rendering of the args (~60
 *                    chars max) for the prompt's repeated-call
 *                    section.
 * @param invokedAt   wall-clock at dispatch, used to render "Ns / Nm /
 *                    Nh / Nd ago" in the prompt.
 * @param resulted    whether this invocation actually produced a result
 *                    (settled `Success`/`Failure`). `false` when the
 *                    invoke settled with a `Pending` outcome — its result
 *                    raced past the frame and never reached the agent
 *                    (sigil #354). Defaults `true` for back-compat. The
 *                    duplicate-call cap counts only `resulted` invocations
 *                    so a retry of a never-resulted slow tool isn't
 *                    punished as a spinning duplicate.
 * @param failed      whether this invocation settled with a
 *                    [[sigil.event.ToolOutcome.Failure]]. A duplicate-call
 *                    loop whose prior identical calls all `failed` is a
 *                    tooling-seam loop, not a capability gap — the
 *                    orchestrator refuses the duplicate but does NOT escalate
 *                    the tier on it (a stronger model issues the same call and
 *                    hits the same failure; sigil #371). Defaults `false`.
 */
case class RecentToolInvocation(toolName: ToolName,
                                argsHash: String,
                                argsPreview: String,
                                invokedAt: Timestamp,
                                resulted: Boolean = true,
                                failed: Boolean = false) derives RW
