package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.diagnostics.ContextManagementInsight
import sigil.participant.ParticipantId

/**
 * Notice fired when the curator detects that this turn's resolved
 * pinned memories occupy more than `pinnedShareWarningThreshold`
 * (default 20%) of the model's context window. Carries the same
 * actionable details that the in-conversation `_budgetWarning` entry
 * includes, for apps that want non-conversational treatment (admin
 * UI banner, external observability, alerting).
 *
 * Apps subscribe via `signals` filtered to `PinnedMemoryBudgetWarning`
 * and render a red badge / banner ("Pinned memories exceed N% of
 * available context — consider reviewing"). Pair with
 * [[sigil.tool.context.ListMemoriesTool]] (with `pinned = Some(true)`)
 * to let the user review and trim.
 *
 * The warning is stateless per turn — re-emitted whenever the
 * threshold is exceeded; apps that want throttling apply it on the
 * subscriber side.
 */
case class PinnedMemoryBudgetWarning(conversationId: Id[Conversation],
                                     modelId: Id[Model],
                                     participantId: ParticipantId,
                                     totalTokens: Int,
                                     contextLength: Int,
                                     sharePct: Double,
                                     largestContributors: List[PinnedMemoryShare],
                                     insights: List[ContextManagementInsight]) extends Notice derives RW

/** A single pinned-memory contributor in
  * [[PinnedMemoryBudgetWarning.largestContributors]] — `(key, tokens)` pair. */
case class PinnedMemoryShare(key: String, tokens: Int) derives RW
