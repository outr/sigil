package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.conversation.MemoryType.given
import sigil.participant.ParticipantId
import sigil.participant.ParticipantId.given
import sigil.spatial.Place

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
 *
 * `createdBy` records the participant who authored the memory —
 * typically the agent that called `save_memory`, the user who
 * dictated a note, etc. Independent of `location`: an agent
 * authors the record but the location belongs to the user whose
 * device produced it. The framework's `persistMemoryFor` /
 * `upsertMemoryByKeyFor` overloads use the active chain to resolve
 * both fields in one shot.
 *
 * `location` records where the memory was formed. The framework's
 * `locationForChain` helper walks the conversation's chain looking
 * for the user (first non-agent participant) and consults
 * `Sigil.locationFor` on them — the same hook
 * `LocationCaptureTransform` uses for messages, so memories see
 * the same coordinate the user's messages do. Defaults to `None`
 * for memories captured without geolocation.
 */
case class ContextMemory(fact: String,
                         source: MemorySource,
                         spaceId: SpaceId,
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
                         createdBy: Option[ParticipantId] = None,
                         location: Option[Place] = None,
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

  /** Tokenized full-text index over key + label + summary + fact + tags.
    * Backs `find_capability`'s BM25-scored memory search (same shape
    * as [[sigil.tool.Tool.searchText]] / [[sigil.skill.Skill.searchText]]).
    * Memory matches in `find_capability` carry only the key + summary
    * — the agent calls `lookup(capabilityType=Memory, name=key)` to
    * pull the full fact when it judges the memory worth the tokens. */
  val searchText: lightdb.field.Field.Tokenized[ContextMemory] =
    field.tokenized("searchText", (m: ContextMemory) =>
      s"${m.key} ${m.label} ${m.summary} ${m.fact} ${m.tags.mkString(" ")}"
    )

  override def id(value: String = Unique()): Id[ContextMemory] = Id(value)
}
