package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.conversation.MemorySpaceId.given

/**
 * A persisted fact the LLM should know about during a conversation.
 * First-class DB record stored in [[sigil.db.SigilDB.memories]] and
 * referenced from [[ConversationContext.memories]] by id — the provider
 * resolves ids to records at render time so updates are visible across
 * every conversation using the memory, without stale embedded copies.
 *
 * `spaceId` scopes the memory to a (app-defined) space — global, per
 * project, per user, etc. `Sigil.findMemories(spaces)`
 * queries by space to assemble the turn's relevant memory set.
 *
 * `extraContext` is app-specific structured metadata (ownership, tags,
 * expiration hints, UI display fields, whatever the app needs).
 */
case class ContextMemory(fact: String,
                         source: MemorySource,
                         spaceId: MemorySpaceId,
                         extraContext: Map[ContextKey, String] = Map.empty,
                         created: Timestamp = Timestamp(),
                         modified: Timestamp = Timestamp(),
                         _id: Id[ContextMemory] = ContextMemory.id())
  extends RecordDocument[ContextMemory]

object ContextMemory extends RecordDocumentModel[ContextMemory] with JsonConversion[ContextMemory] {
  implicit override def rw: RW[ContextMemory] = RW.gen

  val spaceId: I[MemorySpaceId] = field.index(_.spaceId)

  override def id(value: String = Unique()): Id[ContextMemory] = Id(value)
}
