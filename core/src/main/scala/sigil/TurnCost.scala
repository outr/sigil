package sigil

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.TokenUsage

/**
 * A single turn's cost attribution (sigil #406), handed to
 * [[Sigil.onTurnCost]] at the point the framework folds the charge into
 * `Conversation.cost`. It ties together everything a downstream cost ledger
 * needs but which the transient
 * [[sigil.signal.ConversationCostUpdated]] Notice doesn't carry: the
 * triggering participant, the resolved model, the computed USD `cost`, the
 * token `usage`, the active conversation `mode`, and the settling event's id.
 *
 * The default `onTurnCost` is a no-op; apps override it to persist their own
 * ledger row and build itemized breakdowns (per model · mode, by participant,
 * project-to-date). Fires for BOTH user-visible `Message` turns and
 * tool-call-only `ToolInvoke` turns (change_mode / find_capability / …), so
 * every charge is attributable.
 *
 * @param conversationId the conversation the turn belongs to
 * @param eventId        the settling event (`Message` / `ToolInvoke`) charged
 * @param participantId  the acting participant the cost is attributed to
 * @param modelId        the resolved model the turn ran on (registry-canonical
 *                       when known)
 * @param mode           the conversation's active mode name at fold time
 *                       (`ConversationMode.name` = "conversation" unless the
 *                       app switched modes); apps that model their own mode
 *                       separately join it from their per-turn state
 * @param cost           the USD charge for THIS turn (the increment folded
 *                       into `Conversation.cost`), from `Model.pricing` ×
 *                       `usage`
 * @param usage          the turn's token usage
 * @param timestamp      when the charge was folded
 */
case class TurnCost(conversationId: Id[Conversation],
                    eventId: Id[Event],
                    participantId: ParticipantId,
                    modelId: Id[Model],
                    mode: String,
                    cost: BigDecimal,
                    usage: TokenUsage,
                    timestamp: Timestamp)
  derives RW
