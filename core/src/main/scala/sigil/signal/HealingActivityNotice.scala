package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.heal.{CorruptionEvidence, HealingOutcome}

/**
 * Transient operational pulse — emitted alongside the durable audit
 * events ([[sigil.event.ConversationCorruptionDetected]],
 * [[sigil.event.ConversationHealed]],
 * [[sigil.event.HealingExhausted]]) so operators can wire alerting on
 * heal frequency without consuming the durable event store.
 *
 * Treat sustained frequency on any one `strategyName` as a forensic
 * signal: a single strategy firing 100+ times a day means there's a
 * framework bug producing fresh corruption faster than the heal
 * pattern was designed to cover, NOT that the heal is healthy
 * operation.
 */
case class HealingActivityNotice(conversationId: Id[Conversation],
                                 strategyName: String,
                                 detectedCorruption: List[CorruptionEvidence],
                                 outcome: HealingOutcome) extends Notice derives RW
