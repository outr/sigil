package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextKey, ContextMemory, ContextSummary, ConversationView, TurnInput}
import sigil.db.Model
import sigil.information.InformationSummary
import sigil.participant.ParticipantId
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}

/**
 * Default [[ContextCurator]]. Runs a fixed pipeline:
 *
 *   1. [[optimizer]] — cheap, stateless frame cleanup.
 *   2. [[blockExtractor]] — pull long content blocks out to
 *      [[sigil.information.Information]] records (off by default).
 *   3. [[memoryRetriever]] — surface relevant stored memories into
 *      `TurnInput.memories` so they render in the system prompt
 *      (off by default).
 *   4. Build a tentative [[TurnInput]] from the trimmed frames +
 *      extracted catalog entries + retrieved memory ids.
 *   5. Budget check via [[budget]] against the target model's
 *      context length.
 *   6. If over budget: split frames at `max(N/2, keepMinimum)`,
 *      compress the older half via [[compressor]], and swap the new
 *      summary id into the TurnInput.
 *
 * The persistent [[ConversationView]] is never mutated — compression
 * only edits the ephemeral TurnInput that flows to the provider. Any
 * information dropped from the rolling context stays on disk via the
 * event log + (for extracted blocks) the Information catalog, and is
 * surfaced on demand by `search_conversation` / `lookup`.
 *
 * Every pipeline stage has a NoOp default — apps opt in component by
 * component:
 *   - optimizer = StandardContextOptimizer with all rules on
 *   - blockExtractor = NoOpBlockExtractor
 *   - memoryRetriever = NoOpMemoryRetriever
 *   - compressor = NoOpContextCompressor
 *   - budget = Percentage(0.8)
 *
 * Swap any or all as needed.
 */
case class StandardContextCurator(sigil: Sigil,
                                  optimizer: ContextOptimizer = StandardContextOptimizer(),
                                  blockExtractor: BlockExtractor = NoOpBlockExtractor,
                                  memoryRetriever: MemoryRetriever = NoOpMemoryRetriever,
                                  compressor: ContextCompressor = NoOpContextCompressor,
                                  budget: ContextBudget = Percentage(0.8),
                                  keepMinimum: Int = 4,
                                  tokenizer: Tokenizer = HeuristicTokenizer,
                                  criticalShareWarningThreshold: Double = 0.30) extends ContextCurator {

  override def curate(view: ConversationView,
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] = {
    // Build the elide-set from each shipped Tool's `resultTtl`. The
    // standard policy is: any tool declaring `Some(0)` is ephemeral —
    // its ToolResults frame is redundant after the turn settles
    // (because the meaningful effect lives on a projection / System
    // frame / system prompt section). Tools with positive TTLs are
    // treated like `None` here (kept) — turn-count-aware elision is
    // future work and apps can extend this curator for it.
    val elide: Set[String] = sigil.staticTools.iterator
      .collect { case t if t.resultTtl.contains(0) => t.name.value }
      .toSet
    // Bug #73 — pass `chain.head` (the trigger originator, typically
    // the user) as the current-turn source so the optimizer preserves
    // within-turn iterations of ephemeral tools. Without this, mid-
    // turn agent loops on `find_capability` had their prior calls
    // elided across iterations, making each one look like a fresh
    // start to the model and producing identical-call retry loops
    // until `maxAgentIterations` fired.
    val optimizedFrames = optimizer.optimize(view.frames, elide, chain.headOption)

    for {
      blockResult <- blockExtractor.extract(sigil, optimizedFrames)
      postBlockView = view.copy(frames = blockResult.frames)
      memoryResult <- memoryRetriever.retrieve(sigil, postBlockView, chain)
      tentative = TurnInput(
        conversationView = postBlockView,
        criticalMemories = memoryResult.criticalMemories,
        memories = memoryResult.memories,
        information = blockResult.information
      )
      modelOpt <- modelFor(modelId)
      shed <- modelOpt match {
        case Some(model) =>
          budgetResolve(model, postBlockView, blockResult.frames, tentative, modelId, chain, memoryResult, blockResult.information)
        case None =>
          // No catalog record for this modelId. Either the provider
          // forgot to seed [[sigil.cache.ModelRegistry]] (a custom
          // provider's bug — framework-shipped providers seed at
          // construction) or the registry was wiped. Skip budget
          // compression and surface the optimized frames as-is.
          // Better to miss compression than to crash the agent loop
          // on the first turn.
          Task.pure(tentative)
      }
      result <- modelOpt match {
        case Some(model) => attachBudgetWarning(shed, model, memoryResult)
        case None        => Task.pure(shed)
      }
    } yield result
  }

  private def budgetResolve(model: Model,
                            postBlockView: ConversationView,
                            frames: Vector[ContextFrame],
                            tentative: TurnInput,
                            modelId: Id[Model],
                            chain: List[ParticipantId],
                            memoryResult: MemoryRetrievalResult,
                            information: Vector[InformationSummary]): Task[TurnInput] =
    resolveMemoriesAndSummaries(memoryResult).flatMap {
      case (resolvedCritical, resolvedRetrieved) =>
        val cap = budget.tokensFor(model)

        def tokensOf(t: TurnInput, framesArg: Vector[ContextFrame], summariesArg: Vector[ContextSummary]): Int =
          TokenEstimator.estimateCuratorSections(
            frames = framesArg,
            criticalMemories = resolvedCritical,
            memories = if (t.memories.isEmpty) Vector.empty else resolvedRetrieved,
            summaries = summariesArg,
            information = t.information,
            tokenizer = tokenizer
          )

        // Stage 0 — already fits, done.
        if (tokensOf(tentative, frames, Vector.empty) <= cap) Task.pure(tentative)
        else {
          // Stage 1 — drop non-critical retrieved memories. Recoverable
          // via `recall_memory` if the agent decides it needs them.
          val afterStage1 = tentative.copy(memories = Vector.empty)
          if (tokensOf(afterStage1, frames, Vector.empty) <= cap) Task.pure(afterStage1)
          else {
            // Stage 2 — drop Information records the current frames don't
            // reference. Agent can `lookup` them on demand.
            val referenced = referencedInformationIds(frames)
            val keptInformation = information.filter(i => referenced.contains(i.id.value))
            val afterStage2 = afterStage1.copy(information = keptInformation)
            if (tokensOf(afterStage2, frames, Vector.empty) <= cap) Task.pure(afterStage2)
            else {
              // Stage 3 — frame compression. Existing path: split at
              // mid-point, summarise the older half.
              if (frames.size <= keepMinimum) Task.pure(afterStage2)
              else {
                val keep = math.max(keepMinimum, frames.size / 2)
                val (older, newer) = frames.splitAt(frames.size - keep)
                compressor.compress(sigil, modelId, chain, older, postBlockView.conversationId).map {
                  case Some(summary) =>
                    TurnInput(
                      conversationView = postBlockView.copy(frames = newer),
                      criticalMemories = memoryResult.criticalMemories,
                      memories = afterStage2.memories,
                      summaries = Vector(summary._id),
                      information = keptInformation
                    )
                  case None =>
                    // Compressor declined / failed — return Stage 2's
                    // result. Provider pre-flight gate is the next line
                    // of defence.
                    afterStage2
                }
              }
            }
          }
        }
    }

  /** Resolve the criticalMemories / memories id buckets from a
    * [[MemoryRetrievalResult]] to full records via the DB. Same lookup
    * shape as [[sigil.provider.Provider.resolveReferences]]; duplicated
    * here to avoid pulling that private into the curator API. Ids that
    * fail to resolve are dropped silently (the curator's concern is
    * size; missing records are the retriever's bug). */
  private def resolveMemoriesAndSummaries(memResult: MemoryRetrievalResult): Task[(Vector[ContextMemory], Vector[ContextMemory])] = {
    val now = lightdb.time.Timestamp()
    for {
      crit <- Task.sequence(memResult.criticalMemories.toList.map(id =>
                sigil.withDB(_.memories.transaction(_.get(id)))))
      regular <- Task.sequence(memResult.memories.toList.map(id =>
                   sigil.withDB(_.memories.transaction(_.get(id)))))
    } yield (
      crit.flatten.iterator.filterNot(StandardMemoryRetriever.isExpired(_, now)).toVector,
      regular.flatten.iterator.filterNot(StandardMemoryRetriever.isExpired(_, now)).toVector
    )
  }

  /** Information ids referenced inside the current frames. Used by
    * Stage 2 to keep only those entries the agent might actually
    * dereference this turn. */
  private def referencedInformationIds(frames: Vector[ContextFrame]): Set[String] = {
    val needle = "Information["
    frames.iterator.flatMap {
      case t: ContextFrame.Text       => extractIds(t.content, needle)
      case tr: ContextFrame.ToolResult => extractIds(tr.content, needle)
      case tc: ContextFrame.ToolCall   => extractIds(tc.argsJson, needle)
      case s: ContextFrame.System     => extractIds(s.content, needle)
      case _                          => Iterator.empty
    }.toSet
  }

  private def extractIds(content: String, needle: String): Iterator[String] = {
    if (!content.contains(needle)) Iterator.empty
    else {
      val out = List.newBuilder[String]
      var idx = content.indexOf(needle)
      while (idx >= 0) {
        val start = idx + needle.length
        val end = content.indexOf(']', start)
        if (end > start) out += content.substring(start, end)
        idx = content.indexOf(needle, end + 1)
      }
      out.result().iterator
    }
  }

  /** Inject a "Conversation budget" entry into [[TurnInput.extraContext]]
    * when this turn's resolved Critical memories occupy more than
    * [[criticalShareWarningThreshold]] of the model's context. The
    * agent reads this on its next turn alongside the rest of the system
    * prompt; if the user asks "what's filling my context?" or the
    * agent decides to mention proactively, the breakdown is right
    * there. Single entry, replaced each turn — no accumulation, no
    * throttling needed. */
  private def attachBudgetWarning(turnInput: TurnInput,
                                  model: Model,
                                  memResult: MemoryRetrievalResult): Task[TurnInput] =
    if (memResult.criticalMemories.isEmpty) Task.pure(turnInput)
    else resolveCriticalForWarning(memResult).map { criticals =>
      val criticalTokens = TokenEstimator.estimateMemories(criticals, tokenizer)
      val ctxLen = model.contextLength.toInt
      if (ctxLen <= 0 || criticalTokens.toDouble / ctxLen <= criticalShareWarningThreshold) turnInput
      else {
        val pct = (criticalTokens.toDouble / ctxLen * 100).toInt
        val top = criticals
          .map { m =>
            val rendered = if (m.summary.trim.nonEmpty) m.summary else m.fact
            val key = if (m.key.nonEmpty) m.key else m._id.value
            (key, tokenizer.count(rendered))
          }
          .sortBy(-_._2)
          .take(3)
          .map { case (k, n) => s"$k @${n} tok" }
          .mkString(", ")
        val message =
          s"Your critical directives use ~$pct% of this model's context window ($criticalTokens / $ctxLen tok; top: $top). " +
            s"If the user wants to review pinned items, call `list_pinned_memories` and offer them via `respond_options`. " +
            s"Use `unpin_memory(key)` to demote ones the user no longer wants."
        turnInput.copy(extraContext = turnInput.extraContext + (ContextKey("_budgetWarning") -> message))
      }
    }

  /** Resolve the critical-memory ids for the warning calculation only.
    * Distinct from [[resolveMemoriesAndSummaries]] because the budget
    * pass may have already done that work — but the warning runs after
    * `budgetResolve` returns, so we look these up fresh. */
  private def resolveCriticalForWarning(memResult: MemoryRetrievalResult): Task[Vector[ContextMemory]] =
    Task.sequence(memResult.criticalMemories.toList.map(id =>
      sigil.withDB(_.memories.transaction(_.get(id)))
    )).map(_.flatten.toVector)

  /** Look up the target model in [[sigil.cache.ModelRegistry]].
    * Returns `None` when no record exists — the curator's caller
    * then short-circuits to the unbudgeted [[TurnInput]] rather than
    * crashing the agent loop. Apps that want a stricter posture
    * (fail-loud when a provider forgot to seed) extend this curator
    * and override [[curate]] directly. */
  private def modelFor(modelId: Id[Model]): Task[Option[Model]] =
    Task.pure(sigil.cache.find(modelId))
}
