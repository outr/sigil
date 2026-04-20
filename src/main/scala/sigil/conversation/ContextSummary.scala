package sigil.conversation

import fabric.rw.*

/**
 * A summary of an older compressed conversation segment. The curator
 * collapses runs of older messages into summaries when the active message
 * window exceeds the token budget; the summary is injected as a single
 * "Earlier in this conversation:" block before the live messages.
 *
 * `tokenEstimate` is recorded so the curator can budget summaries the same
 * way it budgets messages without re-tokenizing on each turn.
 */
case class ContextSummary(text: String, tokenEstimate: Int) derives RW
