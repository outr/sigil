package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.db.Model
import sigil.event.Event
import sigil.participant.ParticipantId

/**
 * Per-(agent, conversation, model) cache of provider-encoded
 * conversation history. Holds the serialized message-array shape the
 * active provider expects on the wire — chat-completion-style
 * `[{role, content, ...}, ...]` for OpenAI-compatible providers,
 * Anthropic's `messages` array, the OpenAI Responses `input` array,
 * Gemini's `contents`. The framework treats it as opaque text and
 * splices it into the request envelope without re-parsing.
 *
 * Cache key composition: `(agentId, conversationId, modelId)`.
 *   - **agentId** drives perspective: per-viewer visibility filtering
 *     and from-self / from-other framing apply during encoding, so
 *     two agents in the same conversation may produce different
 *     encoded shapes.
 *   - **conversationId** scopes to the conversation.
 *   - **modelId** scopes to the wire shape: providers expect different
 *     formats; encoding per-model means cache hits are zero-conversion
 *     and switching models is a clean cache miss + rebuild.
 *
 * `builtThrough` is the validity cursor — the most-recent
 * [[sigil.event.Event]] id whose `contextFrame` has been folded into
 * `encoded`. The curator's per-turn flow appends every event newer
 * than `builtThrough` (with `contextFrame.isDefined`) to `encoded`
 * via the active provider's `appendFrame`, then advances the cursor.
 *
 * `tokenCount` accumulates incrementally so per-turn budget checks
 * are O(1) rather than re-tokenizing the whole encoded buffer.
 *
 * `lastAccessedAt` drives TTL eviction. Idle caches reclaim space;
 * the surviving caches save real work on the next turn against their
 * (agent, conversation, model) tuple.
 *
 * Bug #26 — replaces the legacy `ConversationView.frames` projection
 * + per-turn re-encoding work with cache-and-rebuild.
 */
case class EncodedContext(agentId: ParticipantId,
                          conversationId: Id[Conversation],
                          modelId: Id[Model],
                          encoded: String = "[]",
                          tokenCount: Long = 0L,
                          builtThrough: Option[Id[Event]] = None,
                          lastAccessedAt: Timestamp = Timestamp(),
                          created: Timestamp = Timestamp(),
                          modified: Timestamp = Timestamp(),
                          _id: Id[EncodedContext] = EncodedContext.id())
  extends RecordDocument[EncodedContext]

object EncodedContext extends RecordDocumentModel[EncodedContext] with JsonConversion[EncodedContext] {
  implicit override def rw: RW[EncodedContext] = RW.gen

  /**
   * Compose a deterministic id from `(agentId, conversationId, modelId)`
   * so lookups are O(1) and the same key always resolves to the same
   * record.
   */
  def idFor(agentId: ParticipantId,
            conversationId: Id[Conversation],
            modelId: Id[Model]): Id[EncodedContext] =
    Id(s"${conversationId.value}:${agentId.value}:${modelId.value}")

  val agentId: I[ParticipantId] = field.index(_.agentId)
  val conversationId: I[Id[Conversation]] = field.index(_.conversationId)
  val modelId: I[Id[Model]] = field.index(_.modelId)
  val lastAccessedAt: I[Timestamp] = field.index(_.lastAccessedAt)

  override def id(value: String = rapid.Unique()): Id[EncodedContext] = Id(value)

  /**
   * Fresh empty cache entry for a new (agent, conversation, model) key.
   */
  def empty(agentId: ParticipantId,
            conversationId: Id[Conversation],
            modelId: Id[Model]): EncodedContext =
    EncodedContext(
      agentId = agentId,
      conversationId = conversationId,
      modelId = modelId,
      _id = idFor(agentId, conversationId, modelId)
    )
}
