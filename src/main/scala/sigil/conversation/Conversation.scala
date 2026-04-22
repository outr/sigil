package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.participant.Participant
import sigil.provider.Mode

/**
 * A conversation is a durable scope for events, the participants involved,
 * and any per-conversation metadata. Stored in [[sigil.db.SigilDB.conversations]].
 *
 * `participants` drives the dispatcher's fan-out: when a Signal lands, the
 * framework reads this list directly and fires any `AgentParticipant` whose
 * `TriggerFilter` predicate matches. Participants serialize through the
 * `Participant` poly — agents are persistent by value (modelId, toolNames,
 * instructions, …), with live `Provider` and `Tool` instances resolved at
 * call time via `Sigil.providerFor` and `ToolFinder.byName`.
 *
 * `currentMode` is the conversation's active operating mode, kept up to
 * date by the framework as [[sigil.event.ModeChange]] events land. All
 * agents acting on this conversation read this field for their next
 * provider request — mode is conversation-level state, not agent-level.
 *
 * `currentTopicId` points at the active [[Topic]] — the thread new events
 * land on and whose label the LLM sees as "Current topic" in the system
 * prompt. `TopicChange` events maintain this pointer; `Sigil.newConversation`
 * bootstraps an initial Topic so the pointer is never dangling.
 *
 * `RecordDocument` brings `created` / `modified` timestamps — useful for
 * "last activity" sorting in UIs.
 */
case class Conversation(currentTopicId: Id[Topic],
                        participants: List[Participant] = Nil,
                        currentMode: Mode = Mode.Conversation,
                        created: Timestamp = Timestamp(),
                        modified: Timestamp = Timestamp(),
                        _id: Id[Conversation] = Conversation.id())
  extends RecordDocument[Conversation] {

  /**
   * Convenience alias for `_id`.
   */
  def id: Id[Conversation] = _id
}

object Conversation extends RecordDocumentModel[Conversation] with JsonConversion[Conversation] {
  implicit override def rw: RW[Conversation] = RW.gen

  override def id(value: String = Unique()): Id[Conversation] = Id(value)
}
