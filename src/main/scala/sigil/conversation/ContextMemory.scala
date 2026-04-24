package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.conversation.MemorySpaceId.given
import sigil.conversation.MemoryType.given

/**
 * A persisted fact the LLM should know about during a conversation.
 * First-class DB record stored in [[sigil.db.SigilDB.memories]] and
 * referenced from [[TurnInput.memories]] by id — the provider
 * resolves ids to records at render time so updates are visible across
 * every conversation using the memory, without stale embedded copies.
 *
 * `spaceId` scopes the memory to a (app-defined) space — global, per
 * project, per user, etc. `Sigil.findMemories(spaces)`
 * queries by space to assemble the turn's relevant memory set.
 *
 * `extraContext` is app-specific structured metadata (ownership, tags,
 * expiration hints, UI display fields, whatever the app needs).
 *
 * Versioning (`validFrom` / `validUntil` / `supersedes` / `supersededBy`)
 * is populated by `Sigil.upsertMemoryByKey` — compression-extracted
 * facts and critical directives bypass it and use `persistMemory` for
 * single-shot inserts where versioning is meaningless.
 */
case class ContextMemory(fact: String,
                         source: MemorySource,
                         spaceId: MemorySpaceId,
                         key: String = "",
                         label: String = "",
                         summary: String = "",
                         tags: Vector[String] = Vector.empty,
                         memoryType: MemoryType = MemoryType.Fact,
                         status: MemoryStatus = MemoryStatus.Approved,
                         confidence: Double = 1.0,
                         pinned: Boolean = false,
                         validFrom: Option[Timestamp] = None,
                         validUntil: Option[Timestamp] = None,
                         supersedes: Option[Id[ContextMemory]] = None,
                         supersededBy: Option[Id[ContextMemory]] = None,
                         accessCount: Int = 0,
                         lastAccessedAt: Timestamp = Timestamp(),
                         conversationId: Option[Id[Conversation]] = None,
                         extraContext: Map[ContextKey, String] = Map.empty,
                         created: Timestamp = Timestamp(),
                         modified: Timestamp = Timestamp(),
                         _id: Id[ContextMemory] = ContextMemory.id())
  extends RecordDocument[ContextMemory]

object ContextMemory extends RecordDocumentModel[ContextMemory] with JsonConversion[ContextMemory] {
  implicit override def rw: RW[ContextMemory] = RW.gen

  // Indexed on string projections rather than the poly / enum types
  // themselves: Lucene's filter backend can't generate equality checks
  // for polymorphic records or Scala 3 enums. Queries compare against
  // the projected string value.
  val spaceIdValue: I[String] = field.index(_.spaceId.value)
  val key: I[String] = field.index(_.key)
  val statusName: I[String] = field.index(_.status.toString)
  val pinned: I[Boolean] = field.index(_.pinned)
  val conversationId: I[Option[Id[Conversation]]] = field.index(_.conversationId)

  override def id(value: String = Unique()): Id[ContextMemory] = Id(value)
}
