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
 */
case class RecentToolInvocation(toolName: ToolName,
                                argsHash: String,
                                argsPreview: String,
                                invokedAt: Timestamp) derives RW
