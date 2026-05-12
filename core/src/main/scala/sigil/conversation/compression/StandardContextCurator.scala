package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, ParticipantProjection, TurnInput}
import sigil.db.Model
import sigil.information.InformationSummary
import sigil.participant.ParticipantId
import sigil.conversation.compression.extract.{MemoryExtractor, NoOpMemoryExtractor}
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}

/**
 * Default [[ContextCurator]]. Bug #26 — sources frames from
 * `db.events` (via [[sigil.event.Event.contextFrame]]) and
 * per-participant projections from `db.participantProjections`
 * directly; no longer materializes a `ConversationView` projection.
 *
 * Per-turn pipeline:
 *
 *   1. Pull frames for the conversation via [[Sigil.framesFor]].
 *   2. [[optimizer]] — cheap, stateless frame cleanup.
 *   3. [[blockExtractor]] — pull long content blocks out to
 *      [[sigil.information.Information]] records (off by default).
 *   4. [[memoryRetriever]] — surface relevant stored memories into
 *      `TurnInput.memories` (off by default).
 *   5. Snapshot the chain's participant projections.
 *   6. Build a tentative [[TurnInput]] from the trimmed frames +
 *      extracted catalog entries + retrieved memory ids + projections.
 *   7. Budget-guard via [[budget]] against the target model's
 *      context length. Multi-stage shed:
 *        - Stage 1 — drop non-critical retrieved memories.
 *        - Stage 2 — drop Information records the frames don't reference.
 *        - Stage 3 — iterative frame compression (per bug #23).
 *
 * Every pipeline stage has a NoOp default — apps opt in component
 * by component.
 */
case class StandardContextCurator(sigil: Sigil,
                                  optimizer: ContextOptimizer = StandardContextOptimizer(),
                                  blockExtractor: BlockExtractor = NoOpBlockExtractor,
                                  memoryRetriever: MemoryRetriever = NoOpMemoryRetriever,
                                  compressor: ContextCompressor = NoOpContextCompressor,
                                  /** Run over the about-to-be-shed slice in stage 3
                                    * (frame compression) BEFORE the slice gets
                                    * collapsed into a summary. Captures durable
                                    * facts hidden inside older frames so they
                                    * survive the lossy compression. Fires on a
                                    * background fiber — failures are logged but
                                    * don't block the curator pipeline. Default
                                    * NoOp; wire a concrete extractor (typically
                                    * [[StandardMemoryExtractor]]) to enable. */
                                  compressionExtractor: MemoryExtractor = NoOpMemoryExtractor,
                                  budget: ContextBudget = Percentage(0.8),
                                  keepMinimum: Int = 4,
                                  tokenizer: Tokenizer = HeuristicTokenizer,
                                  /** Token counter used in the multi-stage `budgetResolve`
                                    * shed. Defaults to [[HeuristicTokenizer]] regardless of
                                    * what `tokenizer` is — budget math runs over the full
                                    * frame vector (50K+ frames on bulk-imported
                                    * conversations) and gets re-run on the survivors of
                                    * every shed stage. A network-backed `tokenizer`
                                    * (`LlamaCppTokenizer` etc.) plugged here would issue
                                    * one HTTP round-trip per unique text per pass — fine
                                    * on a 50-frame chat, multi-minute hangs on a 50K-frame
                                    * import. The heuristic is in-memory, instant, and
                                    * conservative (over-counts ~7-15% which the right
                                    * asymmetry for a pre-flight gate). Apps that genuinely
                                    * need wire-exact budget math override this explicitly;
                                    * everyone else benefits from the cheap default even if
                                    * they wire a network tokenizer for other paths. */
                                  budgetTokenizer: Tokenizer = HeuristicTokenizer,
                                  pinnedShareWarningThreshold: Double = 0.20,
                                  /** Hard cap on the number of frames the per-turn
                                    * curate pass considers. When the conversation has
                                    * more frames than this (typical only on bulk-
                                    * imported histories — 50K+ events from
                                    * `load_claude_state`), only the most-recent
                                    * `maxFramesPerTurn` flow through block extraction,
                                    * memory retrieval, and budget resolution. Older
                                    * frames remain in the durable event log and stay
                                    * reachable via `search_conversation` /
                                    * `recall_memory` / persisted summaries —
                                    * they're just skipped on the hot path so the
                                    * curator doesn't try to summarize the entire
                                    * history every turn. `Int.MaxValue` disables the
                                    * cap (legacy behaviour). Bug #144. */
                                  maxFramesPerTurn: Int = 5000,
                                  /** When `true`, the curator pulls persisted
                                    * `ContextSummary` records via
                                    * [[sigil.Sigil.summariesFor]] and feeds them
                                    * into the turn's `TurnInput.summaries`
                                    * BEFORE the budget gate runs. Apps that
                                    * precompute summaries at import time (the
                                    * "compress once, recall many" pattern) get
                                    * them rendered on every subsequent turn
                                    * without re-paying the compression cost.
                                    * Default `true`; apps that don't use the
                                    * persisted-summary pathway can disable to
                                    * skip the per-turn DB read. Bug #144. */
                                  loadPersistedSummaries: Boolean = true,
                                  /** Optional detector for the
                                    * "paraphrase without action" failure
                                    * mode. When set and a pattern fires,
                                    * an observation is injected into the
                                    * next turn's `TurnInput.extraContext`
                                    * under [[ParaphraseLoopDetector.ContextKeyValue]]
                                    * so the model sees the loop and can
                                    * self-correct rather than being
                                    * silently cleaned up. Default `None`
                                    * — opt-in. */
                                  paraphraseDetector: Option[ParaphraseLoopDetector] = None) extends ContextCurator {

  override def curate(conversationId: Id[Conversation],
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    sigil.runAsFrameworkWorkflow(
      workflowType = "curate",
      label = "Building turn context",
      conversationId = Some(conversationId)
    ) { control =>
      // Bug #60 — visibility for the in-loop work between user turn
      // arrival and chat/completions dispatch. Curate fires every
      // turn; on a fresh conversation it's sub-second (the Notice
      // flickers — fine), after a bulk import it's the user-
      // perceptible window the activity bar needs to surface.
      val elide: Set[String] = sigil.staticTools.iterator
        .collect { case t if t.resultTtl.contains(0) => t.name.value }
        .toSet
      for {
        _             <- control.step("Loading frames")
        rawFrames     <- sigil.framesFor(conversationId)
        visibleFrames = rawFrames.filter(f => sigil.visibilityAllows(f.visibility, chain.lastOption.orNull))
        // Cap the per-turn frame budget. Bulk-imported conversations
        // (50K+ events) flow through curate every turn; without a
        // bound the framework re-runs block extraction + budget
        // resolution over the entire history each time. The most-
        // recent `maxFramesPerTurn` are what the agent typically
        // needs in scope; older frames remain durable and reachable
        // via search / recall / persisted summaries.
        boundedFrames = if (visibleFrames.size <= maxFramesPerTurn) visibleFrames
                        else visibleFrames.takeRight(maxFramesPerTurn)
        optimizedFrames = optimizer.optimize(boundedFrames, elide, chain.headOption)
        _             <- control.step(s"Extracting blocks (${optimizedFrames.size} frames)")
        // Pulse the workflow step every progress callback the
        // extractor fires so the activity bar reflects forward
        // motion on bulk imports instead of sitting on the same
        // label for minutes. Default cadence (every 500 frames)
        // keeps small-conversation noise low.
        progressCb    = (i: Int, n: Int) => control.step(s"Extracting blocks ($i / $n)")
        blockResult   <- blockExtractor.extract(sigil, optimizedFrames, progressCb)
        _             <- control.step("Retrieving memories")
        memoryResult  <- memoryRetriever.retrieve(sigil, conversationId, blockResult.frames, chain)
        // Pull persisted summaries (the "compress once, recall many"
        // path). Compression-time records from earlier turns AND
        // import-time hierarchical summaries (when an app wires
        // `compressOnImport`) both flow through here so older history
        // is represented without being in the raw-frame stream.
        persistedSummaries <-
          if (loadPersistedSummaries) sigil.summariesFor(conversationId).map(_.map(_._id).toVector)
          else Task.pure(Vector.empty)
        projections   <- loadProjections(conversationId, chain)
        tentative     = injectParaphraseObservation(
          TurnInput(
            conversationId = conversationId,
            frames = blockResult.frames,
            participantProjections = projections,
            criticalMemories = memoryResult.criticalMemories,
            memories = memoryResult.memories,
            summaries = persistedSummaries,
            information = blockResult.information
          ),
          chain
        )
        modelOpt      <- modelFor(modelId)
        _             <- control.step("Resolving token budget")
        shed          <- modelOpt match {
          case Some(model) =>
            budgetResolve(model, tentative, modelId, chain, memoryResult, blockResult.information)
          case None =>
            Task.pure(tentative)
        }
        result        <- modelOpt match {
          case Some(model) => attachBudgetWarning(shed, model, memoryResult, modelId, chain, conversationId)
          case None        => Task.pure(shed)
        }
      } yield result
    }

  /** Snapshot every chain participant's projection from the
    * persistent collection. Empty when none recorded yet. */
  private def loadProjections(conversationId: Id[Conversation],
                              chain: List[ParticipantId]): Task[Map[ParticipantId, ParticipantProjection]] =
    Task.sequence(chain.distinct.map { pid =>
      sigil.projectionFor(pid, conversationId).map(p => pid -> p)
    }).map(_.toMap)

  private def budgetResolve(model: Model,
                            tentative: TurnInput,
                            modelId: Id[Model],
                            chain: List[ParticipantId],
                            memoryResult: MemoryRetrievalResult,
                            information: Vector[InformationSummary]): Task[TurnInput] =
    for {
      memTuple <- resolveMemoriesAndSummaries(memoryResult)
      resolvedSummaries <- resolveSummaries(tentative.summaries)
      out <- {
        val (resolvedCritical, resolvedRetrieved) = memTuple
        val cap = budget.tokensFor(model)

        // The persisted-summary section is always rendered when
        // budget allows. When the budget gets tight the curator
        // sheds it BEFORE frame compression (cheaper, app-authored
        // — sheds preserve frames). Bug #144.
        def tokensOf(t: TurnInput, framesArg: Vector[ContextFrame], summariesArg: Vector[ContextSummary]): Int =
          TokenEstimator.estimateCuratorSections(
            frames = framesArg,
            criticalMemories = resolvedCritical,
            memories = if (t.memories.isEmpty) Vector.empty else resolvedRetrieved,
            summaries = summariesArg,
            information = t.information,
            tokenizer = budgetTokenizer
          )

        val frames = tentative.frames

        if (tokensOf(tentative, frames, resolvedSummaries) <= cap) Task.pure(tentative)
        else {
          // Stage 1 — drop non-critical retrieved memories.
          val afterStage1 = tentative.copy(memories = Vector.empty)
          if (tokensOf(afterStage1, frames, resolvedSummaries) <= cap) Task.pure(afterStage1)
          else {
            // Stage 2 — drop unreferenced Information.
            val referenced = referencedInformationIds(frames)
            val keptInformation = information.filter(i => referenced.contains(i.id.value))
            val afterStage2 = afterStage1.copy(information = keptInformation)
            if (tokensOf(afterStage2, frames, resolvedSummaries) <= cap) Task.pure(afterStage2)
            else {
              // Stage 2b — drop persisted summaries (cheap-shed
              // before invoking compressor). Apps relying on
              // import-time summaries pay the cost only when the
              // budget genuinely can't accommodate them.
              val afterStage2b = afterStage2.copy(summaries = Vector.empty)
              if (tokensOf(afterStage2b, frames, Vector.empty) <= cap) Task.pure(afterStage2b)
              else {
                // Stage 3 — iterative frame compression (bug #23 + #26).
                shedFramesIteratively(
                  kept = frames,
                  droppedSoFar = Vector.empty,
                  summaryCarry = None,
                  cap = cap,
                  modelId = modelId,
                  chain = chain,
                  conversationId = tentative.conversationId,
                  tokensOfKept = (kept, summaryOpt) =>
                    tokensOf(afterStage2b, kept, summaryOpt.toVector)
                ).map { case (newerKept, summaryOpt) =>
                  summaryOpt match {
                    case Some(summary) =>
                      afterStage2b.copy(
                        frames = newerKept,
                        summaries = Vector(summary._id)
                      )
                    case None =>
                      afterStage2b.copy(frames = newerKept)
                  }
                }
              }
            }
          }
        }
      }
    } yield out

  /** Resolve persisted-summary ids on `TurnInput.summaries` to full
    * records via the DB. Bug #144 — the curator's budget-gate math
    * needs the rendered token cost of every summary in the tentative
    * TurnInput; without resolution the gate under-counts and the
    * provider sees a request that's bigger than the budget computed. */
  private def resolveSummaries(ids: Vector[Id[ContextSummary]]): Task[Vector[ContextSummary]] =
    if (ids.isEmpty) Task.pure(Vector.empty)
    else Task.sequence(ids.toList.map { id =>
      sigil.withDB(_.summaries.transaction(_.get(id)))
    }).map(_.flatten.toVector)

  /** Iterative Stage 3 shed (bug #23 — preserves the iteration model
    * inside the new bug-#26 architecture). Each pass either fits, hits
    * `keepMinimum`, or falls through on a compressor refusal. When the
    * input exceeds `cap × 3`, jump straight to the floor for a single
    * aggressive collapse instead of rounds of halving. */
  private def shedFramesIteratively(kept: Vector[ContextFrame],
                                    droppedSoFar: Vector[ContextFrame],
                                    summaryCarry: Option[ContextSummary],
                                    cap: Int,
                                    modelId: Id[Model],
                                    chain: List[ParticipantId],
                                    conversationId: Id[Conversation],
                                    tokensOfKept: (Vector[ContextFrame], Option[ContextSummary]) => Int)
      : Task[(Vector[ContextFrame], Option[ContextSummary])] = {
    val current = tokensOfKept(kept, summaryCarry)
    if (current <= cap || kept.size <= keepMinimum) Task.pure((kept, summaryCarry))
    else {
      val aggressive = current > cap * 3
      val keep =
        if (aggressive) keepMinimum
        else math.max(keepMinimum, kept.size / 2)
      val (older, newer) = kept.splitAt(kept.size - keep)
      val toSummarize = droppedSoFar ++ older
      // Fire compression-time extraction over the shed slice on a
      // background fiber. Captures durable facts before the slice
      // is collapsed into a lossy summary; failures don't block.
      compressionExtractor.extractFromFrames(sigil, conversationId, modelId, chain, older)
        .handleError { e =>
          Task(scribe.warn(s"compressionExtractor failed for $conversationId: ${e.getMessage}")).map(_ => Nil)
        }.startUnit()
      compressor.compress(sigil, modelId, chain, rapid.Stream.emits(toSummarize), conversationId).flatMap {
        case Some(summary) =>
          shedFramesIteratively(
            kept = newer,
            droppedSoFar = toSummarize,
            summaryCarry = Some(summary),
            cap = cap,
            modelId = modelId,
            chain = chain,
            conversationId = conversationId,
            tokensOfKept = tokensOfKept
          )
        case None =>
          Task.pure((kept, summaryCarry))
      }
    }
  }

  /** Resolve the criticalMemories / memories id buckets from a
    * [[MemoryRetrievalResult]] to full records via the DB. */
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

  /** Information ids referenced inside the current frames. */
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

  private[compression] def extractIds(content: String, needle: String): Iterator[String] = {
    if (!content.contains(needle)) Iterator.empty
    else {
      val out = List.newBuilder[String]
      var idx = content.indexOf(needle)
      while (idx >= 0) {
        val start = idx + needle.length
        val end = content.indexOf(']', start)
        if (end > start) out += content.substring(start, end)
        // Advance past the current match's prefix regardless of
        // whether a closing `]` was found. The previous formulation
        // (`content.indexOf(needle, end + 1)`) reduced to
        // `content.indexOf(needle, 0)` when `end == -1` and re-matched
        // the same unterminated reference forever — bricked the
        // curator for any conversation whose frame content contained
        // `Information[` without a closing bracket (user-pasted text,
        // imported transcript fragments).
        idx = content.indexOf(needle, start)
      }
      out.result().iterator
    }
  }

  private def attachBudgetWarning(turnInput: TurnInput,
                                  model: Model,
                                  memResult: MemoryRetrievalResult,
                                  modelId: Id[Model],
                                  chain: List[ParticipantId],
                                  conversationId: Id[Conversation]): Task[TurnInput] =
    if (memResult.criticalMemories.isEmpty) Task.pure(turnInput)
    else resolveCriticalForWarning(memResult).flatMap { pinnedMemories =>
      val pinnedTokens = TokenEstimator.estimateMemories(pinnedMemories, tokenizer)
      val ctxLen = model.contextLength.toInt
      if (ctxLen <= 0 || pinnedTokens.toDouble / ctxLen <= pinnedShareWarningThreshold) Task.pure(turnInput)
      else {
        val pct = (pinnedTokens.toDouble / ctxLen * 100).toInt
        val sharePct = pinnedTokens.toDouble / ctxLen
        val ranked = pinnedMemories
          .map { m =>
            val rendered = if (m.summary.trim.nonEmpty) m.summary else m.fact
            val key = m.key.getOrElse(m._id.value)
            (key, tokenizer.count(rendered))
          }
          .sortBy(-_._2)
        val top3 = ranked.take(3)
        val topRender = top3.map { case (k, n) => s"$k @${n} tok" }.mkString(", ")
        val message =
          s"Your pinned directives use ~$pct% of this model's context window ($pinnedTokens / $ctxLen tok; top: $topRender). " +
            s"If the user wants to review pinned items, call `list_memories(pinned=true)` and offer them via `respond_options`. " +
            s"Use `unpin_memory(key)` to remove ones the user no longer wants."
        val notice = _root_.sigil.signal.PinnedMemoryBudgetWarning(
          conversationId = conversationId,
          modelId = modelId,
          participantId = chain.lastOption.getOrElse(chain.headOption.orNull),
          totalTokens = pinnedTokens,
          contextLength = ctxLen,
          sharePct = sharePct,
          largestContributors = top3.map { case (k, n) => _root_.sigil.signal.PinnedMemoryShare(k, n) }.toList,
          insights = Nil
        )
        sigil.publish(notice).map { _ =>
          turnInput.copy(extraContext = turnInput.extraContext + (ContextKey("_budgetWarning") -> message))
        }
      }
    }

  private def resolveCriticalForWarning(memResult: MemoryRetrievalResult): Task[Vector[ContextMemory]] =
    Task.sequence(memResult.criticalMemories.toList.map(id =>
      sigil.withDB(_.memories.transaction(_.get(id)))
    )).map(_.flatten.toVector)

  private def modelFor(modelId: Id[Model]): Task[Option[Model]] =
    Task.pure(sigil.cache.find(modelId))

  /** Run [[paraphraseDetector]] over the turn's frame history; on a
    * hit, append the observation to `extraContext` under
    * [[ParaphraseLoopDetector.ContextKeyValue]]. No-op when the
    * detector is not configured or the chain has no agent
    * participant the detector can scope to. */
  private def injectParaphraseObservation(turn: TurnInput, chain: List[ParticipantId]): TurnInput =
    paraphraseDetector match {
      case None => turn
      case Some(detector) =>
        chain.lastOption match {
          case None => turn
          case Some(agentId) =>
            detector.detect(turn.frames, agentId) match {
              case None          => turn
              case Some(pattern) =>
                turn.copy(extraContext = turn.extraContext +
                  (_root_.sigil.conversation.ContextKey(ParaphraseLoopDetector.ContextKeyValue) -> pattern.render()))
            }
        }
    }
}
