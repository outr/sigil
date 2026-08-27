package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.conversation.MemoryType.given
import sigil.embedding.EmbeddingRef
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.participant.ParticipantId.given
import sigil.provider.Mode
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
 * facts and pinned directives bypass it and use `persistMemory` for
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
                         label: String,
                         summary: String,
                         source: MemorySource,
                         spaceId: SpaceId,
                         key: Option[String] = None,
                         keywords: Vector[String] = Vector.empty,
                         memoryType: MemoryType = MemoryType.Fact,
                         status: MemoryStatus = MemoryStatus.Approved,
                         confidence: Double = 1.0,
                         pinned: Boolean = false,
                         validFrom: Option[Timestamp] = None,
                         validUntil: Option[Timestamp] = None,
                         expiresAt: Option[Timestamp] = None,
                         justification: Option[String] = None,
                         supersedes: Option[Id[ContextMemory]] = None,
                         supersededBy: Option[Id[ContextMemory]] = None,
                         accessCount: Int = 0,
                         lastAccessedAt: Timestamp = Timestamp(),
                         conversationId: Option[Id[Conversation]] = None,
                         createdBy: Option[ParticipantId] = None,
                         location: Option[Place] = None,
                         extraContext: Map[ContextKey, String] = Map.empty,
                         /** Per-[[Mode]] retrieval gate. When non-empty, the
                           * memory only surfaces during turns whose
                           * [[Conversation.currentMode]] id is in this set.
                           * Empty (the default) = the memory is universal —
                           * surfaces regardless of current mode.
                           *
                           * Scoping critical directives to the mode they
                           * apply to avoids loading them into every turn of
                           * conversations that swap modes — a "always create
                           * failing tests before fixing" directive captured
                           * during Coding mode is wasted prompt budget when
                           * the conversation switches back to Conversation
                           * mode. */
                         modeAffinity: Set[Id[Mode]] = Set.empty,
                         /** Event-grain provenance — the durable [[Event]] ids
                           * of the exchange this memory was extracted from.
                           * Populated by the per-turn extractor (the turn's
                           * event window) and the compression-time extractor
                           * (the summarised chunk's frames). Keyed-upsert
                           * semantics: a `Refreshed` write unions the prior
                           * record's ids with the new extraction's; a
                           * `Versioned` write starts the new record with only
                           * the new extraction's ids (the superseded version
                           * keeps its own). Empty for memories written outside
                           * any conversation (seeding, app-side writes). */
                         sourceEventIds: List[Id[Event]] = Nil,
                         /** Provenance for this record's point in
                           * [[sigil.vector.VectorIndex]] — stamped by the
                           * framework's index paths, cleared when the point is
                           * evicted. `None` means "no live point": either
                           * vector search isn't wired, or the record has never
                           * been indexed, or its point was deleted. Compared
                           * against `fact` and the live
                           * [[sigil.embedding.EmbeddingProvider]] by
                           * [[sigil.maintenance.EmbeddingReconcileTask]] to
                           * find rows whose vector drifted from the store. */
                         embedding: Option[EmbeddingRef] = None,
                         created: Timestamp = Timestamp(),
                         modified: Timestamp = Timestamp(),
                         _id: Id[ContextMemory] = ContextMemory.id())
  extends RecordDocument[ContextMemory] {

  /** The shared retrieval gate: `true` when this record is the current
    * version of its slot (`validUntil` unset), its `status` is
    * [[MemoryStatus.Approved]], and it has not expired (`expiresAt`
    * unset or in the future). Every retrieval surface — the hybrid
    * retriever legs, the pinned load, [[sigil.Sigil.searchMemories]],
    * [[sigil.Sigil.findMemories]] — applies this predicate, so
    * superseded versions, pending / rejected records, and expired
    * records never reach a prompt. Versioning and history queries
    * (`memoryHistory`, the keyed-upsert write path,
    * `listPendingMemories`) deliberately bypass it. */
  def isRecallable(now: Timestamp): Boolean =
    validUntil.isEmpty &&
      status == MemoryStatus.Approved &&
      !expiresAt.exists(_.value <= now.value)

  /** The embedding space this record's live vector point belongs to,
    * or [[EmbeddingRef.Unindexed]] when it holds no point built from
    * the `fact` it currently carries. Purely document-local, so it can
    * be indexed: comparing it to the running provider's identity turns
    * "never indexed", "text drifted since indexing", and "embedder
    * changed" into one inequality. */
  def embeddingIdentity: String =
    embedding.filter(_.contentHash == EmbeddingRef.hash(fact)).map(_.identity).getOrElse(EmbeddingRef.Unindexed)

  /** `true` when a drifted vector point on this record is worth
    * re-embedding. Archived versions, non-Approved records, and empty
    * facts hold no point by design, and an expiring memory's point
    * outlives its usefulness anyway — sweeping any of them would spend
    * embedding calls on rows no retrieval can reach, and (for the
    * evicted ones) fight the eviction that cleared the stamp. */
  def embeddingReconcilable: Boolean =
    fact.nonEmpty && validUntil.isEmpty && status == MemoryStatus.Approved && expiresAt.isEmpty
}

object ContextMemory extends RecordDocumentModel[ContextMemory] with JsonConversion[ContextMemory] {
  implicit override def rw: RW[ContextMemory] = RW.gen

  // Indexed on string projections so callers can query by a space's
  // `value` / a status's name without constructing the poly or enum
  // instance (the retrieval paths carry `Set[SpaceId]` scopes, whose
  // members may be app subtypes the framework can't name).
  val spaceIdValue: I[String] = field.index(_.spaceId.value)
  val key: I[Option[String]] = field.index(_.key)
  val statusName: I[String] = field.index(_.status.toString)
  val pinned: I[Boolean] = field.index(_.pinned)
  val conversationId: I[Option[Id[Conversation]]] = field.index(_.conversationId)
  /** `expiresAt.value` projected for indexing — Lucene can't filter on
    * the polymorphic `Timestamp` directly. Records with no expiry
    * project as `None`. Backs the opt-in expiry sweep
    * ([[Sigil.expiredMemorySweepInterval]]). */
  val expiresAtValue: I[Option[Long]] = field.index("expiresAtValue", _.expiresAt.map(_.value))

  /** Projections of [[ContextMemory.embeddingIdentity]] and
    * [[ContextMemory.embeddingReconcilable]]. Together they let
    * [[sigil.maintenance.EmbeddingReconcileTask]] find every drifted
    * row with a single indexed query — `embeddingIdentity !== <live
    * provider identity> && embeddingReconcilable === true` — which
    * matches nothing at all when the index is in sync, so the sweep
    * costs one empty query rather than a scan. */
  val embeddingIdentity: I[String] = field.index("embeddingIdentity", _.embeddingIdentity)
  val embeddingReconcilable: I[Boolean] = field.index("embeddingReconcilable", _.embeddingReconcilable)

  /** Tokenized full-text index over key + label + summary + fact +
    * keywords. Backs `find_capability`'s BM25-scored memory search
    * AND the lexical leg of [[StandardMemoryRetriever]]'s hybrid
    * retrieval. `keywords` is the union of agent-supplied tags and
    * the unified classifier's LLM-extracted retrieval signals — both
    * arrive in the same field so semantically-relevant queries that
    * don't share lexical tokens with `fact` / `summary` still hit.
    * Memory matches in `find_capability` carry only the key + summary
    * — the agent calls `lookup(capabilityType=Memory, name=key)` to
    * pull the full fact when it judges the memory worth the tokens. */
  val searchText: lightdb.field.Field.Tokenized[ContextMemory] =
    field.tokenized("searchText", (m: ContextMemory) => {
      val k = m.key.getOrElse("")
      s"$k ${m.label} ${m.summary} ${m.fact} ${m.keywords.mkString(" ")}"
    })

  override def id(value: String = Unique()): Id[ContextMemory] = Id(value)
}
