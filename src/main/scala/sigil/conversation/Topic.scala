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
 * Topics live as flat siblings inside a conversation. The conversation
 * carries a [[Conversation.topics]] stack of [[TopicEntry]]s — the last
 * entry is the active thread. New events land on the active topic until
 * the agent declares a shift via the `topicLabel` / `topicSummary` fields
 * on a `respond` call; the orchestrator's two-step classifier resolves
 * whether that shift is a [[TopicChangeKind.Switch]] (push or truncate
 * the stack) or a [[TopicChangeKind.Rename]] (mutate this Topic's label
 * in place).
 *
 * Context isn't filtered by topic at render time — the provider sees the
 * whole conversation. Topics serve as a search and promotion key, and
 * supply the "Current topic" + "Previous topics" lines in the system
 * prompt with their `label` and `summary` so the classifier can match
 * proposed labels against semantic context, not just text.
 *
 * `summary` is a 1-2 sentence description of what's been discussed under
 * this topic. Used both as UI display and as context the classifier sees
 * when deciding whether a new label is the same subject as a prior.
 *
 * `labelLocked` prevents the classifier's Refine path from overwriting a
 * label a user has pinned.
 */
case class Topic(conversationId: Id[Conversation],
                 label: String,
                 summary: String,
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

  /**
   * Summary used at bootstrap, before any actual subject has emerged.
   * Replaced once a real exchange happens.
   */
  val DefaultSummary: String = "(no subject established yet)"

  val conversationId: I[Id[Conversation]] = field.index(_.conversationId)

  override def id(value: String = Unique()): Id[Topic] = Id(value)
}
