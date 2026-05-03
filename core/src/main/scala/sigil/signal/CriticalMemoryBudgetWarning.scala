package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.diagnostics.ContextManagementInsight
import sigil.participant.ParticipantId

/**
 * Notice fired when the curator detects that the resolved Critical
 * memories for a turn occupy more than `criticalMemoryShareWarning`
 * (default 30%) of the model's context window. Carries the same
 * actionable details that the in-conversation System frame includes,
 * for apps that want non-conversational treatment (admin UI, external
 * observability, alerting).
 *
 * Throttled per conversation: emitted at most once every
 * `criticalMemoryWarningThrottle` turns when over threshold (default
 * 25 turns) so the signal stream isn't noisy.
 */
case class CriticalMemoryBudgetWarning(conversationId: Id[Conversation],
                                       modelId: Id[Model],
                                       participantId: ParticipantId,
                                       totalTokens: Int,
                                       contextLength: Int,
                                       sharePct: Double,
                                       largestContributors: List[CriticalMemoryShare],
                                       insights: List[ContextManagementInsight]) extends Notice derives RW

/** A single critical-memory contributor in
  * [[CriticalMemoryBudgetWarning.largestContributors]] — `(key, tokens)` pair. */
case class CriticalMemoryShare(key: String, tokens: Int) derives RW
