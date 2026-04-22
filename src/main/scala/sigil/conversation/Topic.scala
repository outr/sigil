package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.participant.ParticipantId

/**
 * A labeled thread within a [[Conversation]]. Every [[sigil.event.Event]]
 * belongs to exactly one Topic (via its `topicId`), which lets search,
 * promotion, and future UI threading carve the event log by topic.
 *
 * Topics are flat siblings inside a conversation — no parent pointers,
 * no tree structure. Each Conversation carries a `currentTopicId`
 * identifying the active thread; new events land on that topic until the
 * agent signals a shift via `topicConfidence` on a `respond` call.
 *
 * Context isn't filtered by topic at render time — the provider sees the
 * whole conversation. Topics serve as a search and promotion key, and as
 * the "current thread label" shown in the system prompt. When sub-topic
 * promotion is added, events scoped to a single topic can be lifted
 * wholesale into a new conversation.
 *
 * `labelLocked` prevents the LLM's medium-confidence rename path from
 * overwriting a label a user has pinned.
 */
case class Topic(conversationId: Id[Conversation],
                 label: String,
                 labelLocked: Boolean = false,
                 createdBy: ParticipantId,
                 created: Timestamp = Timestamp(),
                 modified: Timestamp = Timestamp(),
                 _id: Id[Topic] = Topic.id())
  extends RecordDocument[Topic]

object Topic extends RecordDocumentModel[Topic] with JsonConversion[Topic] {
  implicit override def rw: RW[Topic] = RW.gen

  /**
   * Label used when bootstrapping a fresh conversation's initial Topic.
   * The LLM is expected to rename it on the first `respond` call (the
   * label is a clear "pick one" signal when shown in the system prompt).
   */
  val DefaultLabel: String = "New Conversation"

  val conversationId: I[Id[Conversation]] = field.index(_.conversationId)

  override def id(value: String = Unique()): Id[Topic] = Id(value)
}
