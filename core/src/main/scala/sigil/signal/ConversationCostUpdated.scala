package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Notice emitted by the framework's projection step every time a
 * settled [[sigil.event.Message]] increments
 * [[sigil.conversation.Conversation.cost]] — i.e. the Message carried
 * a `modelId` that resolves to a known
 * [[sigil.db.Model]] in [[sigil.cache.ModelRegistry]] and the
 * resulting per-Message charge is non-zero.
 *
 * `cost` is the new running total for the conversation; `delta` is
 * the increment from this single Message. Both are USD, sourced from
 * [[sigil.db.ModelPricing]] (per-token) multiplied against the
 * Message's [[sigil.provider.TokenUsage]].
 *
 * Clients subscribing to [[sigil.Sigil.signals]] (or
 * `signalsFor(viewer)`) render running cost in real time without
 * having to ship the pricing table to the wire.
 */
case class ConversationCostUpdated(conversationId: Id[Conversation],
                                   cost: BigDecimal,
                                   delta: BigDecimal)
  extends Notice derives RW
