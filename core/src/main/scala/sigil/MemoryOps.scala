package sigil

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.conversation.{ContextMemory, Conversation, MemorySource, MemoryStatus, UpsertMemoryResult}
import sigil.db.Model
import sigil.vector.{VectorPoint, VectorPointId}

/**
 * Memory persistence + classification cluster — the durable write
 * paths for [[ContextMemory]] (single-shot, keyed/versioned, and the
 * chain-enriched convenience overloads), the optional LLM-driven
 * classification that fills retrieval keywords / permanence / space
 * before a write, and the bulk seeding helper.
 *
 * Mixed into [[Sigil]]; every method remains a public (or protected,
 * for the hooks) member of `Sigil`. The self-type reaches the rest of
 * the framework's state (`withDB`, `accessibleSpaces`, `locationForChain`,
 * `framesFor`, `supportsParameter`, `embeddingProvider`, `vectorIndex`).
 */
trait MemoryOps { this: Sigil =>

  /** Soft check on a proposed pinned-memory write — never fails the
    * task. Apps that want hard rejection (e.g. regulated industries
    * where blowing the inviolable share is a real problem) override
    * this hook to fail with their own exception based on the same
    * [[sigil.conversation.CoreContextValidator]] estimates the
    * framework uses for warnings. Default: no-op for any memory,
    * pinned or not. */
  protected def validateCoreContextCap(proposed: ContextMemory): Task[Unit] =
    Task.unit

  /** Persist a new [[ContextMemory]] and return the stored record.
    * When vector search is wired, auto-embeds `memory.fact` and
    * upserts into [[vectorIndex]] with payload
    * `kind=memory, spaceId=…`.
    *
    * Pinned memories (`memory.pinned == true`) pass through the soft
    * [[validateCoreContextCap]] hook (default no-op — apps that want
    * hard rejection override and throw their own exception).
    *
    * If [[memoryClassifierModel]] is set and the supplied
    * `memory.keywords` is empty, the framework runs a one-shot LLM
    * classification (sync) to populate keywords + permanence + space
    * before the write. The classifier respects caller-set fields:
    * non-empty keywords skip the call entirely, explicit
    * `pinned = true` is preserved, and a non-Global caller-set space
    * is preserved. Apps that want fully manual control supply
    * non-empty keywords. */
  def persistMemory(memory: ContextMemory): Task[ContextMemory] =
    validateCoreContextCap(memory).flatMap { _ =>
      enrichMemoryClassification(memory, memory.createdBy.toList).flatMap { enriched =>
        withDB(_.memories.transaction(_.upsert(enriched))).flatMap { stored =>
          indexMemory(stored).map(_ => stored)
        }
      }
    }

  /**
   * Upsert a keyed memory with versioning semantics:
   *   - If no prior memory exists at `(spaceId, key)` → insert with
   *     `validFrom = now`, return the new record.
   *   - If the prior memory's `fact` matches → refresh metadata
   *     (label, summary, keywords, memoryType, confidence, pinned,
   *     extraContext, modeAffinity, expiresAt, justification,
   *     location, modified) in place, keep same `_id` and the
   *     original `conversationId` / `validFrom`. Returns the
   *     refreshed record.
   *   - If the prior memory's `fact` differs → archive the prior
   *     (`validUntil = now`, `supersededBy = new._id`) and insert the
   *     new memory with `supersedes = prior._id`, `validFrom = now`.
   *     Returns the new record.
   *
   * Empty `key` is rejected — un-keyed memories must use
   * [[persistMemory]] (the single-shot path; no versioning).
   */
  def upsertMemoryByKey(memory: ContextMemory): Task[UpsertMemoryResult] = {
    if (!memory.key.exists(_.nonEmpty))
      Task.error(new IllegalArgumentException("upsertMemoryByKey requires Some(non-empty key); use persistMemory for un-keyed inserts"))
    else validateCoreContextCap(memory).flatMap { _ =>
      enrichMemoryClassification(memory, memory.createdBy.toList).flatMap(upsertMemoryByKeyImpl)
    }
  }

  private def upsertMemoryByKeyImpl(memory: ContextMemory): Task[UpsertMemoryResult] =
    upsertMemoryByKeyWrite(memory).flatMap { result =>
      indexMemory(result.memory).map(_ => result)
    }

  /** The DB-write half of [[upsertMemoryByKeyImpl]] — applies the
    * versioning rules and persists, but does NOT index. The single
    * path re-attaches [[indexMemory]]; the batched path defers to one
    * [[indexMemoriesBatch]] across every written record. */
  private def upsertMemoryByKeyWrite(memory: ContextMemory): Task[UpsertMemoryResult] =
    withDB { db =>
      db.memories.transaction { tx =>
        import lightdb.filter.*
        tx.query
          .filter(m => (m.spaceIdValue === memory.spaceId.value) && (m.key === memory.key))
          .toList
          .flatMap { sameKey =>
            sameKey.find(_.validUntil.isEmpty) match {
              case None =>
                val fresh = memory.copy(validFrom = Some(Timestamp()), modified = Timestamp())
                tx.upsert(fresh).map(_ => UpsertMemoryResult.Stored(fresh))
              case Some(prior) if prior.fact == memory.fact =>
                val refreshed = prior.copy(
                  label = memory.label,
                  summary = memory.summary,
                  keywords = memory.keywords,
                  memoryType = memory.memoryType,
                  confidence = memory.confidence,
                  pinned = memory.pinned,
                  extraContext = memory.extraContext,
                  modeAffinity = memory.modeAffinity,
                  expiresAt = memory.expiresAt,
                  justification = memory.justification,
                  location = memory.location,
                  modified = Timestamp()
                )
                tx.upsert(refreshed).map(_ => UpsertMemoryResult.Refreshed(refreshed))
              case Some(prior) =>
                val now = Timestamp()
                val fresh = memory.copy(
                  supersedes = Some(prior._id),
                  validFrom = Some(now),
                  modified = now
                )
                val archived = prior.copy(
                  validUntil = Some(now),
                  supersededBy = Some(fresh._id),
                  modified = now
                )
                tx.upsert(fresh).flatMap(_ => tx.upsert(archived)).map(_ => UpsertMemoryResult.Versioned(fresh, archived))
            }
          }
      }
    }

  /**
   * Persist an in-place mutation of an existing memory record and
   * refresh its vector-index entry so the payload metadata (space,
   * fact text) stays consistent with the store. Skips classification
   * and the pinned-cap hook — intended for metadata mutations
   * (pin / unpin / move), not for new facts; those go through
   * [[persistMemory]] / [[upsertMemoryByKey]].
   */
  def updateMemory(memory: ContextMemory): Task[ContextMemory] = {
    val stamped = memory.copy(modified = Timestamp())
    withDB(_.memories.transaction(_.upsert(stamped))).flatMap { stored =>
      indexMemory(stored).map(_ => stored)
    }
  }

  /**
   * Convenience overload: persist a memory and auto-fill `createdBy`
   * + `location` from the active chain. `createdBy` resolves to the
   * immediate caller (`chain.last` — typically the agent that
   * authored the memory); `location` resolves via [[locationForChain]]
   * (walks chain for the user, consults [[locationFor]]). Either
   * field is preserved when the caller already set it.
   *
   * Apps that have a `TurnContext` should prefer this over the bare
   * [[persistMemory]] — every memory created from inside an agent
   * turn benefits from auto-attribution + auto-location.
   */
  def persistMemoryFor(memory: ContextMemory,
                       chain: List[sigil.participant.ParticipantId],
                       conversationId: Id[Conversation]): Task[ContextMemory] =
    enrich(memory, chain, conversationId).flatMap(persistMemory)

  /** Convenience overload of [[upsertMemoryByKey]] with the same
    * `createdBy` + `location` auto-fill behavior as [[persistMemoryFor]]. */
  def upsertMemoryByKeyFor(memory: ContextMemory,
                           chain: List[sigil.participant.ParticipantId],
                           conversationId: Id[Conversation]): Task[UpsertMemoryResult] =
    enrich(memory, chain, conversationId).flatMap(upsertMemoryByKey)

  /**
   * Seed a [[SpaceId]] with a list of declarative natural-language
   * statements at account-creation time (or any moment the app has
   * known facts that haven't yet shown up in conversation):
   *
   * {{{
   *   sigil.initializeMemories(
   *     space      = UserSpace(userId),
   *     statements = List(
   *       "My first name is Matt",
   *       "My last name is Hicks",
   *       "My email address is matt@outr.com",
   *       "I'm 46 years old"
   *     ),
   *     modelId    = extractionModelId,
   *     chain      = List(theUserId, theAgentId)
   *   )
   * }}}
   *
   * The framework wraps the statements in an extraction prompt and
   * runs them through [[ExtractMemoriesTool]] via [[ConsultTool]] —
   * one LLM round-trip per call regardless of statement count, so
   * the model can disambiguate keys cross-statement (e.g. it
   * produces `user.first_name` + `user.last_name` rather than
   * collapsing both into `user.name`).
   *
   * Each result becomes a [[ContextMemory]] with
   * `source = MemorySource.UserInput`, `status = MemoryStatus.Approved`,
   * and `pinned = pinAll` (default `true` — declarative identity
   * facts are almost always always-loaded). Keyed entries route
   * through [[upsertMemoryByKey]] (idempotent — re-running with the
   * same seeds refreshes rather than duplicates); keyless entries
   * fall back to [[persistMemory]].
   *
   * Apps that want fine-grained per-fact control over pinning skip
   * this helper and construct [[ContextMemory]] records directly.
   */
  def initializeMemories(space: SpaceId,
                         statements: List[String],
                         modelId: Id[Model],
                         chain: List[sigil.participant.ParticipantId],
                         pinAll: Boolean = true,
                         systemPrompt: String = Sigil.DefaultInitializationSystemPrompt): Task[List[ContextMemory]] = {
    val cleaned = statements.iterator.map(_.trim).filter(_.nonEmpty).toList
    if (cleaned.isEmpty) Task.pure(Nil)
    else {
      val numbered = cleaned.iterator.zipWithIndex.map { case (s, i) => s"  ${i + 1}. $s" }.mkString("\n")
      val userPrompt =
        s"""Convert each statement below into a durable memory via the `extract_memories` tool.
           |One memory per statement. Use a stable `key` rooted at the user's identity (e.g.
           |"user.first_name", "user.email") so future updates can version the slot.
           |
           |Statements:
           |$numbered""".stripMargin
      sigil.tool.consult.ConsultTool.invoke[sigil.tool.consult.ExtractMemoriesInput](
        sigil = this,
        modelId = modelId,
        chain = chain,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        tool = sigil.tool.consult.ExtractMemoriesTool
      ).flatMap {
        case None => Task.pure(Nil)
        case Some(result) =>
          val kept = result.memories.filter(_.content.trim.nonEmpty)
          val memories = kept.map { m =>
            ContextMemory(
              fact       = m.content,
              label      = if (m.label.trim.nonEmpty) m.label else m.key.getOrElse("memory"),
              summary    = m.content,
              source     = MemorySource.UserInput,
              spaceId    = space,
              key        = m.key,
              keywords   = m.tags.toVector,
              pinned     = pinAll,
              status     = MemoryStatus.Approved,
              createdBy  = chain.lastOption
            )
          }
          // Seeded outside any conversation, so leave `conversationId`
          // None and skip the `*For` variants' location lookup. The
          // whole seed list shares one batched embedding request.
          persistMemories(memories)
      }
    }
  }

  /** Internal helper — fold chain-derived `createdBy` + `location`
    * onto a memory without overwriting fields the caller already set. */
  private def enrich(memory: ContextMemory,
                     chain: List[sigil.participant.ParticipantId],
                     conversationId: Id[Conversation]): Task[ContextMemory] = {
    val withCreator =
      if (memory.createdBy.isDefined) memory
      else chain.lastOption match {
        case Some(p) => memory.copy(createdBy = Some(p))
        case None    => memory
      }
    val withConv =
      if (withCreator.conversationId.isDefined) withCreator
      else withCreator.copy(conversationId = Some(conversationId))
    if (withConv.location.isDefined) Task.pure(withConv)
    else locationForChain(chain, conversationId).map {
      case Some(place) => withConv.copy(location = Some(place))
      case None        => withConv
    }
  }

  /** Model used by [[persistMemory]] / [[upsertMemoryByKey]] to extract
    * retrieval keywords for the memory's content (sync — blocks the
    * write on a one-shot LLM call). When `None` the framework skips
    * extraction and the memory persists with whatever keywords the
    * caller supplied (often empty); the lexical retriever then matches
    * only on label / summary / fact / tags via the existing tokenized
    * `searchText` field, which works less well for memories whose
    * surface vocabulary doesn't share tokens with future queries.
    *
    * Apps wanting topical retrieval to actually work over their memory
    * collection set this to a small / fast model — the classification
    * is a single short-list response per memory, not a reasoning task. */
  def memoryClassifierModel: Option[Id[Model]] = None

  /** Run [[sigil.tool.consult.ClassifyMemoryTool]] against the memory's
    * content when [[memoryClassifierModel]] is set and the caller didn't
    * already supply keywords. Returns the input memory enriched with
    * keywords + permanence (`pinned`) + space (or unchanged on opt-out
    * / classification failure — never blocks persist on an LLM hiccup).
    *
    * Caller-set fields are respected:
    *   - `memory.keywords` non-empty → skip the classifier entirely
    *     (caller has explicit keywords; nothing to enrich).
    *   - `memory.pinned == true` → keep pinned even if classifier says
    *     `Once` (caller deliberately pinned).
    *   - `memory.spaceId` other than [[sigil.GlobalSpace]] → keep the
    *     caller's explicit space (the classifier's choice only fills
    *     in when the caller defaulted to global). */
  private def enrichMemoryClassification(memory: ContextMemory,
                                          chain: List[sigil.participant.ParticipantId]
                                         ): Task[ContextMemory] =
    if (memory.keywords.nonEmpty) Task.pure(memory)
    else memoryClassifierModel match {
      case None => Task.pure(memory)
      case Some(modelId) =>
        for {
          accessible <- memory.conversationId match {
                          case Some(convId) => accessibleSpaces(chain, convId).map(_ + GlobalSpace)
                          case None         => accessibleSpaces(chain).map(_ + GlobalSpace)
                        }
          recentMsg  <- recentUserMessageText(memory.conversationId)
          enriched   <- runMemoryClassifier(memory, chain, modelId, accessible, recentMsg)
        } yield enriched
    }

  private def runMemoryClassifier(memory: ContextMemory,
                                   chain: List[sigil.participant.ParticipantId],
                                   modelId: Id[Model],
                                   accessibleSpaces: Set[SpaceId],
                                   recentUserMessage: Option[String]): Task[ContextMemory] = {
    val spaceCatalog =
      if (accessibleSpaces.isEmpty) "  (none — only global available)"
      else accessibleSpaces.toList.sortBy(_.value).map { s =>
        val desc = s.description.fold("")(d => s" — $d")
        s"  - value=\"${s.value}\" displayName=\"${s.displayName}\"$desc"
      }.mkString("\n")
    val rendered = renderMemoryForClassification(memory)
    val userMsgBlock = recentUserMessage match {
      case Some(text) => s"\n\nUser's recent message (the trigger for this save):\n$text"
      case None       => ""
    }
    val systemPrompt =
      """You classify a memory the framework is about to persist. Decide three things in one call:
        |
        |1. keywords (5-10 retrieval-shaped tokens)
        |2. permanence ("Once" or "Always") — based on imperative cues in the user's recent message
        |3. space (one accessible space `value` or "ambiguous") — most-specific applicable
        |
        |See the tool description for the rules. When unsure on permanence, default to "Once".
        |When unsure on space, output "ambiguous" and supply ambiguityReason.""".stripMargin
    val userPrompt =
      s"""Memory to classify:
         |$rendered
         |
         |Accessible spaces:
         |$spaceCatalog$userMsgBlock
         |
         |Return the classification.""".stripMargin
    val settings = {
      val base = sigil.provider.GenerationSettings(
        outputTokenCap = sigil.provider.OutputTokenCap.Below(220),
        reasoningMode  = sigil.provider.ReasoningMode.Off
      )
      if (supportsParameter(modelId, "temperature")) base.copy(temperature = Some(0.0))
      else base
    }
    sigil.tool.consult.ConsultTool.invoke[sigil.tool.consult.ClassifyMemoryInput](
      sigil = this,
      modelId = modelId,
      chain = chain,
      systemPrompt = systemPrompt,
      userPrompt = userPrompt,
      tool = sigil.tool.consult.ClassifyMemoryTool,
      generationSettings = settings
    ).map {
      case None => memory
      case Some(input) => applyClassifierOutput(memory, input, accessibleSpaces)
    }.handleError { e =>
      Task {
        scribe.warn(s"memory classification failed (${e.getClass.getSimpleName}: ${e.getMessage}) — persisting unclassified")
        memory
      }
    }
  }

  /** Apply classifier output to the memory record, respecting caller-set
    * fields. Unrecognised permanence falls back to keeping the caller's
    * value; ambiguous space leaves the caller's space intact and emits
    * a scribe warning (apps that want to surface ambiguity to the user
    * subscribe to the warning via their log infra, or pre-classify
    * explicitly via [[sigil.Sigil.classifyMemoryDecision]]). */
  private def applyClassifierOutput(memory: ContextMemory,
                                     input: sigil.tool.consult.ClassifyMemoryInput,
                                     accessibleSpaces: Set[SpaceId]): ContextMemory = {
    val cleanedKeywords = input.keywords.iterator.map(_.trim.toLowerCase).filter(_.nonEmpty).toVector.distinct
    val withKeywords = if (cleanedKeywords.isEmpty) memory else memory.copy(keywords = cleanedKeywords)

    val withPinned = input.permanence match {
      case sigil.conversation.Permanence.Always => withKeywords.copy(pinned = true)
      case sigil.conversation.Permanence.Once   => withKeywords  // keep caller's pinned value (default false)
    }

    val classifierSpace = input.space.trim
    val withSpace =
      if (classifierSpace.equalsIgnoreCase("ambiguous")) {
        val reason = input.ambiguityReason.getOrElse("(no reason supplied by classifier)")
        val keyOrId = if (memory.key.nonEmpty) memory.key else memory._id.value
        scribe.warn(
          s"memory classifier returned 'ambiguous' for memory key='$keyOrId' " +
            s"(fallback space='${memory.spaceId.value}'); reason: $reason"
        )
        withPinned
      }
      else if (memory.spaceId != GlobalSpace) withPinned  // caller picked explicitly
      else accessibleSpaces.find(_.value == classifierSpace) match {
        case Some(picked) => withPinned.copy(spaceId = picked)
        case None         => withPinned
      }

    withSpace
  }

  /** Look up the most recent non-agent message text in a conversation —
    * the LLM uses this to detect imperative cues. Returns None when no
    * conversation context is available. */
  private def recentUserMessageText(conversationId: Option[Id[Conversation]]): Task[Option[String]] =
    conversationId match {
      case None => Task.pure(None)
      case Some(convId) =>
        framesFor(convId).map { frames =>
          frames.reverseIterator.collectFirst {
            case t: sigil.conversation.ContextFrame.Text
              if !t.participantId.isInstanceOf[sigil.participant.AgentParticipantId] => t.content
          }
        }
    }

  /** Render a memory in a compact form for the classifier. */
  private def renderMemoryForClassification(memory: ContextMemory): String = {
    val sb = new StringBuilder
    sb.append(s"Label: ${memory.label}\n")
    memory.key.foreach(k => sb.append(s"Key: $k\n"))
    sb.append(s"Summary: ${memory.summary}\n")
    sb.append(s"Fact: ${memory.fact}")
    if (memory.keywords.nonEmpty) sb.append(s"\nExisting keywords: ${memory.keywords.mkString(", ")}")
    sb.toString
  }

  /** `true` when both [[embeddingProvider]] and [[vectorIndex]] are
    * non-NoOp — the flag the framework checks before auto-embedding on
    * persist or attempting vector-backed search. */
  protected final def vectorWired: Boolean =
    embeddingProvider.dimensions > 0 && (vectorIndex ne sigil.vector.NoOpVectorIndex)

  /** Embed a memory's `fact` and upsert it into [[vectorIndex]]. No-op
    * when vector search isn't wired or the fact is empty; vector
    * failures are logged and swallowed so a persist never fails on an
    * index hiccup. */
  private final def indexMemory(m: ContextMemory): Task[Unit] =
    if (!vectorWired || m.fact.isEmpty) Task.unit
    else embeddingProvider.embed(m.fact).flatMap { vec =>
      vectorIndex.upsert(memoryVectorPoint(m, vec))
    }.handleError { e =>
      Task(scribe.warn(s"Vector index failed for memory ${m._id.value}: ${e.getMessage}"))
    }

  /** Build the [[VectorPoint]] for a memory — shared by the single and
    * batched index paths so the payload shape stays in one place. */
  private final def memoryVectorPoint(m: ContextMemory, vec: Vector[Double]): VectorPoint =
    VectorPoint(
      id = VectorPointId(m._id.value),
      vector = vec,
      payload = Map(
        "kind" -> "memory",
        "memoryId" -> m._id.value,
        "spaceId" -> m.spaceId.value,
        sigil.vector.HybridSearch.TextKey -> m.fact
      )
    )

  /** Embed and index a list of memories with a single batched embedding
    * request and a single batched vector upsert — the bulk equivalent
    * of [[indexMemory]]. Memories with an empty `fact` are skipped.
    * No-op when vector search isn't wired; failures are logged and
    * swallowed so a bulk persist never fails on an index hiccup. */
  private final def indexMemoriesBatch(memories: List[ContextMemory]): Task[Unit] = {
    val indexable = memories.filter(_.fact.nonEmpty)
    if (!vectorWired || indexable.isEmpty) Task.unit
    else embeddingProvider.embedBatch(indexable.map(_.fact)).flatMap { vectors =>
      val points = indexable.iterator.zip(vectors.iterator).map { case (m, vec) =>
        memoryVectorPoint(m, vec)
      }.toList
      vectorIndex.upsertBatch(points)
    }.handleError { e =>
      Task(scribe.warn(s"Vector index failed for memory batch (${indexable.size} records): ${e.getMessage}"))
    }
  }

  /**
   * Persist a list of un-keyed memories, embedding every record with a
   * single batched [[sigil.embedding.EmbeddingProvider.embedBatch]]
   * call and a single batched [[sigil.vector.VectorIndex.upsertBatch]]
   * — the bulk-write equivalent of calling [[persistMemory]] N times,
   * but with one embedding HTTP request instead of N.
   *
   * Each record still passes through [[validateCoreContextCap]] and
   * [[enrichMemoryClassification]] individually (classification is an
   * LLM call per memory; batching that is out of scope here). Only the
   * vector-index step is batched. Returns the stored records in input
   * order.
   *
   * Records carrying a non-empty `key` route through the versioned
   * [[upsertMemoryByKey]] write path per-record; keyless records take
   * the single-shot insert. Either way the vector-index step is
   * deferred and batched across the whole list.
   */
  def persistMemories(memories: List[ContextMemory]): Task[List[ContextMemory]] =
    if (memories.isEmpty) Task.pure(Nil)
    else Task.sequence(memories.map { memory =>
      validateCoreContextCap(memory).flatMap { _ =>
        enrichMemoryClassification(memory, memory.createdBy.toList).flatMap { enriched =>
          if (enriched.key.exists(_.nonEmpty))
            upsertMemoryByKeyWrite(enriched).map(_.memory)
          else
            withDB(_.memories.transaction(_.upsert(enriched)))
        }
      }
    }).flatMap { stored =>
      indexMemoriesBatch(stored).map(_ => stored)
    }

  /**
   * Bulk-persist equivalent of [[persistMemoryFor]] /
   * [[upsertMemoryByKeyFor]] — chain-enriches every record
   * (`createdBy` + `location`), then routes through [[persistMemories]]
   * so the whole list shares a single batched embedding request.
   *
   * The framework's per-turn memory extractor and compression-time
   * extractor use this so a turn that yields N memories costs one
   * embedding HTTP call, not N.
   */
  def persistMemoriesFor(memories: List[ContextMemory],
                         chain: List[sigil.participant.ParticipantId],
                         conversationId: Id[Conversation]): Task[List[ContextMemory]] =
    if (memories.isEmpty) Task.pure(Nil)
    else Task.sequence(memories.map(m => enrich(m, chain, conversationId)))
      .flatMap(persistMemories)
}
