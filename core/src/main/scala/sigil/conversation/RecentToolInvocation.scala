package sigil.conversation

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.event.Event
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
 * @param refusal     set when the framework declined to dispatch this call
 *                    rather than running it (see
 *                    [[sigil.event.DispatchRefusal]]). Such an entry is
 *                    `resulted = false` like a raced one, but for the
 *                    opposite reason — nothing ran and nothing is coming —
 *                    so the raced-reissue redirect must skip it and the
 *                    duplicate-call cap counts it as a refusal already
 *                    served rather than as a call still owed an answer.
 * @param invokeId    the originating [[sigil.event.ToolInvoke]]'s id. One
 *                    dispatch owns exactly one entry: the invoke reaches
 *                    `Complete` before its outcome settles, and the later
 *                    settle UPDATES this entry in place rather than adding a
 *                    second one — every consumer counts entries, so a second
 *                    entry would double the repeat count the prompt reports
 *                    and let a placeholder that never raced trip the
 *                    raced-reissue redirect. `None` on rows persisted before
 *                    the id was recorded; those never match and fall off the
 *                    window normally.
 */
case class RecentToolInvocation(toolName: ToolName,
                                argsHash: String,
                                argsPreview: String,
                                invokedAt: Timestamp,
                                resulted: Boolean = true,
                                failed: Boolean = false,
                                refusal: Option[sigil.event.DispatchRefusal] = None,
                                invokeId: Option[Id[Event]] = None) derives RW
