package sigil.event

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Topic

/**
 * Classifies what kind of topic transition a [[TopicChange]] records.
 *
 *   - [[Switch]] — the active topic changed. `previousTopicId` identifies
 *     the topic that was active before; the [[TopicChange.topicId]] is
 *     the newly-active topic. High-confidence (>0.8) signals from the
 *     LLM produce a Switch, as does an explicit app-level promotion.
 *   - [[Rename]] — the active topic stayed the same but its label was
 *     updated. `previousLabel` is the prior label; the new label is on
 *     the event itself. Medium-confidence (0.3–0.8) signals produce a
 *     Rename, and only on unlocked topics.
 */
enum TopicChangeKind derives RW {
  case Switch(previousTopicId: Id[Topic])
  case Rename(previousLabel: String)
}
