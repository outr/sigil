package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.heal.{CorruptionEvidence, HealingOutcome}

/**
     9|  * Transient operational pulse — emitted alongside the durable audit
    10|  * events ([[sigil.event.ConversationCorruptionDetected]],
    11|  * [[sigil.event.ConversationHealed]],
    12|  * [[sigil.event.HealingExhausted]]) so operators can wire alerting on
    13|  * heal frequency without consuming the durable event store.
    14|  */
case class HealingActivityNotice(conversationId: Id[Conversation],
                                 strategyName: String,
                                 detectedCorruption: List[CorruptionEvidence],
                                 outcome: HealingOutcome) extends ConversationNotice derives RW
