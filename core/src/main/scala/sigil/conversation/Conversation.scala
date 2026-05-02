package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.{GlobalSpace, SpaceId}
import sigil.participant.Participant
import sigil.provider.{ConversationMode, Mode}

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
 * `topics` is the navigation stack of [[TopicEntry]]s for this
 * conversation. The LAST entry is the active thread (`currentTopic`); the
 * preceding entries are subjects the conversation has been on before and
 * could return to. The framework maintains this stack via
 * [[sigil.event.TopicChange]] events:
 *
 *   - `Switch` to a new label → push a new entry
 *   - `Switch` to a label already on the stack → truncate the stack back
 *     to that entry (the natural "return to prior subject" flow)
 *   - `Rename` → mutate the active entry in place
 *
 * Each `TopicEntry` carries the Topic's id plus a denormalized `label` +
 * `summary` so the system prompt and UI sidebar can render the stack
 * without a join. Rename events update both the Topic record and the
 * matching stack entries to keep these in sync.
 *
 * `Sigil.newConversation` bootstraps an initial entry so `topics.last`
 * always resolves.
 *
 * `RecordDocument` brings `created` / `modified` timestamps — useful for
 * "last activity" sorting in UIs.
 */
case class Conversation(topics: List[TopicEntry],
                        participants: List[Participant] = Nil,
                        currentMode: Mode = ConversationMode,
                        space: SpaceId = GlobalSpace,
                        clearedAt: Option[Timestamp] = None,
                        created: Timestamp = Timestamp(),
                        modified: Timestamp = Timestamp(),
                        _id: Id[Conversation] = Conversation.id())
  extends RecordDocument[Conversation] {

  /**
   * Convenience alias for `_id`.
   */
  def id: Id[Conversation] = _id

  /**
   * The active topic — last entry on the stack. Throws if the stack is
   * empty (which violates the invariant that `newConversation` upholds).
   */
  def currentTopic: TopicEntry = topics.lastOption.getOrElse {
    throw new IllegalStateException(
      s"Conversation $_id has no topics — newConversation must be used to bootstrap one."
    )
  }

  /**
   * Convenience alias for the active topic's id. Most call sites that
   * used to read `currentTopicId` migrate to this.
   */
  def currentTopicId: Id[Topic] = currentTopic.id

  /**
   * Entries earlier than the active topic — i.e. priors the conversation
   * could return to. Empty when this conversation only has its bootstrap
   * topic.
   */
  def previousTopics: List[TopicEntry] = topics.dropRight(1)
}

object Conversation extends RecordDocumentModel[Conversation] with JsonConversion[Conversation] {
  implicit override def rw: RW[Conversation] = RW.gen

  override def id(value: String = Unique()): Id[Conversation] = Id(value)
}
