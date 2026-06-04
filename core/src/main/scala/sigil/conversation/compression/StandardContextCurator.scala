package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{
  ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, ParticipantProjection, ToolCallState, TurnInput
}
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
                                  /**
                                   * Run over the about-to-be-shed slice in stage 3
                                   * (frame compression) BEFORE the slice gets
                                   * collapsed into a summary. Captures durable
                                   * facts hidden inside older frames so they
                                   * survive the lossy compression. Fires on a
                                   * background fiber — failures are logged but
                                   * don't block the curator pipeline. Default
                                   * NoOp; wire a concrete extractor (typically
                                   * [[StandardMemoryExtractor]]) to enable.
                                   */
                                  compressionExtractor: MemoryExtractor = NoOpMemoryExtractor,
                                  budget: ContextBudget = Percentage(0.8),
                                  keepMinimum: Int = 4,
                                  tokenizer: Tokenizer = HeuristicTokenizer,
                                  /**
                                   * Token counter used in the multi-stage `budgetResolve`
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
                                   * they wire a network tokenizer for other paths.
                                   */
                                  budgetTokenizer: Tokenizer = HeuristicTokenizer,
                                  pinnedShareWarningThreshold: Double = 0.20,
                                  /**
                                   * Hard cap on the number of frames the per-turn
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
                                   * cap (legacy behaviour). Bug #144.
                                   */
                                  maxFramesPerTurn: Int = 5000,
                                  /**
                                   * When `true`, the curator pulls persisted
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
                                   * skip the per-turn DB read. Bug #144.
                                   */
                                  loadPersistedSummaries: Boolean = true,
                                  /**
                                   * Optional detector for the
                                   * "paraphrase without action" failure
                                   * mode. When set and a pattern fires,
                                   * an observation is injected into the
                                   * next turn's `TurnInput.extraContext`
                                   * under [[ParaphraseLoopDetector.ContextKeyValue]]
                                   * so the model sees the loop and can
                                   * self-correct rather than being
                                   * silently cleaned up. Default `None`
                                   * — opt-in.
                                   */
                                  paraphraseDetector: Option[ParaphraseLoopDetector] = None)
  extends ContextCurator {

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
        _ <- control.step("Loading frames")
        // Load persisted summaries first so the per-turn frame
        // filter can skip events that an intra-turn summary
        // (sigil #285) already subsumes. Without this ordering the
        // frame slice would carry both the originals AND the
        // summary text on every subsequent turn.
        allSummaries <- if (loadPersistedSummaries) sigil.summariesFor(conversationId)
        else Task.pure(List.empty[ContextSummary])
        // Build the elision set across every summary's coversEventIds.
        // Empty (the common case) means no per-event filtering kicks
        // in and frames flow through as before.
        elidedEvents: Set[Id[_root_.sigil.event.Event]] =
          allSummaries.iterator.flatMap(_.coversEventIds).toSet
        rawFrames <- sigil.framesFor(conversationId)
        visibleFrames = rawFrames.filter(f =>
          sigil.visibilityAllows(f.visibility, chain.lastOption.orNull) &&
            !elidedEvents.contains(f.sourceEventId))
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
        _ <- control.step(s"Extracting blocks (${optimizedFrames.size} frames)")
        // Pulse the workflow step every progress callback the
        // extractor fires so the activity bar reflects forward
        // motion on bulk imports instead of sitting on the same
        // label for minutes. Default cadence (every 500 frames)
        // keeps small-conversation noise low.
        progressCb = (i: Int, n: Int) => control.step(s"Extracting blocks ($i / $n)")
        blockResult <- blockExtractor.extract(sigil, optimizedFrames, progressCb)
        // Sigil #288 — rewrite ContextFrame.ToolCall.argsJson to
        // truncate fields the tool opts into externalization. The
        // durable event log is untouched; only the per-turn prompt
        // shrinks. The agent recovers original payloads via
        // `search_conversation` if needed.
        externalizedFrames <- externalizeToolUseFields(sigil, blockResult.frames)
        // Sigil #289 — keep only the most-recent N image-bearing
        // ToolCall frames; stub older ones so megabytes of stale
        // previews don't accumulate in the wire prompt.
        deImagedFrames = StandardContextCurator.supersedeOlderImages(
          externalizedFrames,
          sigil.keepRecentImages
        )
        externalizedBlock = blockResult.copy(frames = deImagedFrames)
        _ <- control.step("Retrieving memories")
        memoryResult <- memoryRetriever.retrieve(sigil, conversationId, externalizedBlock.frames, chain)
        // Pull persisted summaries — compression-time records from
        // earlier turns + any narrative summaries an app's UX
        // generated explicitly via `MemoryContextCompressor.compressHierarchical`.
        // Older history is represented this way without re-occupying
        // the raw-frame stream every turn.
        persistedSummaries =
          if (loadPersistedSummaries) allSummaries.map(_._id).toVector
          else Vector.empty
        projections <- loadProjections(conversationId, chain)
        tentative = injectParaphraseObservation(
          TurnInput(
            conversationId = conversationId,
            frames = externalizedBlock.frames,
            participantProjections = projections,
            criticalMemories = memoryResult.criticalMemories,
            memories = memoryResult.memories,
            summaries = persistedSummaries,
            information = externalizedBlock.information
          ),
          chain
        )
        modelOpt <- modelFor(modelId)
        _ <- control.step("Resolving token budget")
        shed <- modelOpt match {
          case Some(model) =>
            budgetResolve(model, tentative, modelId, chain, memoryResult, externalizedBlock.information)
          case None =>
            Task.pure(tentative)
        }
        result <- modelOpt match {
          case Some(model) => attachBudgetWarning(shed, model, memoryResult, modelId, chain, conversationId)
          case None => Task.pure(shed)
        }
      } yield result
    }

  /**
   * Sigil #288 — rewrite ContextFrame.ToolCall.argsJson values for
   * tool-opted-in fields that exceed [[Sigil.inlineToolUseContentThreshold]].
   * Resolves each distinct toolName via [[Sigil.findTools]] once per
   * curate call; per-frame walk just applies the cached opt-in set.
   *
   * Only `ToolCallState.Complete` frames externalize — `Active` frames
   * are mid-turn debug projections where the agent might still be
   * processing the in-flight tool_use; we don't truncate those.
   */
  private def externalizeToolUseFields(sigil: Sigil,
                                       frames: Vector[ContextFrame]): Task[Vector[ContextFrame]] = {
    val threshold = sigil.inlineToolUseContentThreshold
    if (threshold == Long.MaxValue) return Task.pure(frames)
    val candidates: Vector[ContextFrame.ToolCall] = frames.collect {
      case tc: ContextFrame.ToolCall if tc.state.isInstanceOf[ToolCallState.Complete] => tc
    }
    if (candidates.isEmpty) return Task.pure(frames)
    val toolNames = candidates.iterator.map(_.toolName).toSet
    Task.sequence(toolNames.toList.map(n => sigil.findTools.byName(n).map(opt => n -> opt))).flatMap { resolutions =>
      val externalizableByName: Map[_root_.sigil.tool.ToolName, Set[String]] = resolutions.collect {
        case (n, Some(tool)) if tool.externalizableInputFields.nonEmpty =>
          n -> tool.externalizableInputFields
      }.toMap
      if (externalizableByName.isEmpty) Task.pure(frames)
      else Task {
        frames.map {
          case tc: ContextFrame.ToolCall if tc.state.isInstanceOf[ToolCallState.Complete] =>
            externalizableByName.get(tc.toolName) match {
              case Some(fields) =>
                val rewritten = StandardContextCurator.rewriteOversizedFields(tc.argsJson, fields, threshold)
                if (rewritten eq tc.argsJson) tc else tc.copy(argsJson = rewritten)
              case None => tc
            }
          case other => other
        }
      }
    }
  }

  /**
   * Snapshot every chain participant's projection from the
   * persistent collection. Empty when none recorded yet.
   */
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
              else compactLargeFrames(tentative.conversationId, frames).flatMap { compacted =>
                // Stage 2c — elide oversized tool-result / message frames
                // to a short summary + reload-id (#316). Targets the
                // actual budget bloat (a giant grep result, a huge
                // message) WITHOUT dropping any frame; full content stays
                // durable and re-examinable via reload_content(eventId). This
                // runs before the lossy frame shed, so the common case
                // (one oversized tool result) never reaches Stage 3.
                val afterStage2c = afterStage2b.copy(frames = compacted)
                if (tokensOf(afterStage2c, compacted, Vector.empty) <= cap) Task.pure(afterStage2c)
                else {
                  // Stage 3 — last-resort frame shed for sheer history
                  // length. Resolve the invariant-protected
                  // `sourceEventId`s once so the shed never folds the user
                  // task or cleaves a paired tool exchange.
                  resolveProtectedEventIds(tentative.conversationId, compacted).flatMap { protectedIds =>
                    shedFramesIteratively(
                      kept = compacted,
                      droppedSoFar = Vector.empty,
                      summaryCarry = None,
                      cap = cap,
                      modelId = modelId,
                      chain = chain,
                      conversationId = tentative.conversationId,
                      protectedSourceEventIds = protectedIds,
                      tokensOfKept = (kept, summaryOpt) =>
                        tokensOf(afterStage2c, kept, summaryOpt.toVector)
                    )
                  }.flatMap { case (newerKept, summaryOpt) =>
                    summaryOpt match {
                      case Some(summary) =>
                        // Bug #147 — advance the conversation's
                        // `clearedAt` watermark to the timestamp of
                        // the LAST shed frame's source event so the
                        // next turn's `framesFor` filters them out.
                        // Without this, the same shed re-fires every
                        // turn forever — the summary lands but the
                        // frames it replaced come right back. The
                        // advance is capped below the current user task
                        // by `advanceClearedAt` (#316), so old history
                        // sheds while the task never does.
                        val shedSlice = compacted.dropRight(newerKept.size)
                        val advance: Task[Unit] = shedSlice.lastOption match {
                          case Some(boundary) =>
                            sigil.withDB(_.eventsTransaction(tentative.conversationId)(_.get(boundary.sourceEventId))).flatMap {
                              case Some(ev) =>
                                sigil.advanceClearedAt(tentative.conversationId, ev.timestamp)
                                  .handleError(_ => Task.unit)
                              case None => Task.unit
                            }
                          case None => Task.unit
                        }
                        advance.map(_ =>
                          afterStage2c.copy(
                            frames = newerKept,
                            summaries = Vector(summary._id)
                          ))
                      case None =>
                        Task.pure(afterStage2c.copy(frames = newerKept))
                    }
                  }
                }
              }
            }
          }
        }
      }
    } yield out

  /**
   * Resolve persisted-summary ids on `TurnInput.summaries` to full
   * records via the DB. Bug #144 — the curator's budget-gate math
   * needs the rendered token cost of every summary in the tentative
   * TurnInput; without resolution the gate under-counts and the
   * provider sees a request that's bigger than the budget computed.
   */
  private def resolveSummaries(ids: Vector[Id[ContextSummary]]): Task[Vector[ContextSummary]] =
    if (ids.isEmpty) Task.pure(Vector.empty)
    else sigil.withDB(_.summaries.transaction { tx =>
      // Sigil bug #170 — N gets share one transaction. Transaction
      // setup is the dominant cost (RocksDB snapshot + iterator);
      // amortising it across the id list collapses an N-step wait
      // into a single setup pair.
      Task.sequence(ids.toList.map(tx.get)).map(_.flatten.toVector)
    })

  /**
   * Iterative Stage 3 shed (bug #23 — preserves the iteration model
   * inside the new bug-#26 architecture). Each pass either fits, hits
   * `keepMinimum`, or falls through on a compressor refusal. When the
   * input exceeds `cap × 3`, jump straight to the floor for a single
   * aggressive collapse instead of rounds of halving.
   *
   * `protectedSourceEventIds` is the union of every
   * [[CompactionInvariant]] applicable to the slice's events. The
   * split point is adjusted forward (older direction) so no
   * protected event lands in the `older` half; protects paired tool
   * exchanges and structurally load-bearing events from being folded.
   */
  private def shedFramesIteratively(kept: Vector[ContextFrame],
                                    droppedSoFar: Vector[ContextFrame],
                                    summaryCarry: Option[ContextSummary],
                                    cap: Int,
                                    modelId: Id[Model],
                                    chain: List[ParticipantId],
                                    conversationId: Id[Conversation],
                                    protectedSourceEventIds: Set[Id[_root_.sigil.event.Event]],
                                    tokensOfKept: (Vector[ContextFrame], Option[ContextSummary]) => Int)
    : Task[(Vector[ContextFrame], Option[ContextSummary])] = {
    val current = tokensOfKept(kept, summaryCarry)
    if (current <= cap || kept.size <= keepMinimum) Task.pure((kept, summaryCarry))
    else {
      val aggressive = current > cap * 3
      val keep =
        if (aggressive) keepMinimum
        else math.max(keepMinimum, kept.size / 2)
      val initialSplit = kept.size - keep
      val safeSplit = adjustSplitForInvariants(kept, initialSplit, protectedSourceEventIds)
      if (safeSplit <= 0) Task.pure((kept, summaryCarry))
      else {
        val (older, newer) = kept.splitAt(safeSplit)
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
              protectedSourceEventIds = protectedSourceEventIds,
              tokensOfKept = tokensOfKept
            )
          case None =>
            Task.pure((kept, summaryCarry))
        }
      }
    }
  }

  /**
   * Walk the split point earlier until no protected frame lands in
   * the `older` half. Returns 0 when every preceding frame is
   * protected (the shed becomes a no-op for this iteration).
   */
  private def adjustSplitForInvariants(frames: Vector[ContextFrame],
                                       initialSplit: Int,
                                       protectedIds: Set[Id[_root_.sigil.event.Event]]): Int =
    if (protectedIds.isEmpty) initialSplit
    else {
      var s = initialSplit
      while (s > 0 && protectedIds.contains(frames(s - 1).sourceEventId)) s -= 1
      s
    }

  /**
   * Load the events backing `frames`, run every
   * [[CompactionInvariant]] in [[sigil.Sigil.compactionInvariants]]
   * against the result, and return the union of protected
   * `sourceEventId`s. Best-effort: a DB hiccup or a missing event
   * row degrades to an empty set so the shed still makes progress.
   */
  private def resolveProtectedEventIds(conversationId: Id[Conversation],
                                       frames: Vector[ContextFrame]): Task[Set[Id[_root_.sigil.event.Event]]] = {
    val invariants = sigil.compactionInvariants
    if (invariants.isEmpty || frames.isEmpty) Task.pure(Set.empty)
    else {
      val ids = frames.map(_.sourceEventId).distinct
      sigil.withDB(_.eventsTransaction(conversationId) { tx =>
        Task.sequence(ids.toList.map(tx.get)).map(_.flatten.toVector)
      }).map { events =>
        val sorted = events.sortBy(_.timestamp.value)
        val ctx = TurnEventsContext(conversationId = conversationId)
        invariants.iterator.flatMap(_.applicableIds(sorted, ctx)).toSet
      }.handleError(_ => Task.pure(Set.empty))
    }
  }

  /**
   * Frames whose rendered content exceeds this elide to summary+id
   * under budget pressure (#316). High enough that ordinary messages
   * pass through untouched; low enough that a bulk tool result or a
   * giant paste is caught.
   */
  private val frameElisionThreshold: Int = 2000

  /**
   * Characters of the original content kept as the elision's gist
   * when the tool author supplied no summary.
   */
  private val frameElisionHeadChars: Int = 240

  /**
   * #316 — per-frame budget elision. Replace oversized tool-result and
   * message frame content with a short gist + a `reload_content(eventId)`
   * reload pointer, keeping the frame in place. Non-destructive: the
   * durable event retains full content, re-examinable via reload_content.
   * Prefers the tool author's `ToolInvoke.summary` for the gist (this
   * runs only on the over-budget path, over oversized frames, so the
   * per-frame event lookup is rare), falling back to a head excerpt.
   */
  private def compactLargeFrames(conversationId: Id[Conversation],
                                 frames: Vector[ContextFrame]): Task[Vector[ContextFrame]] =
    Task.sequence(frames.map {
      case tc: ContextFrame.ToolCall =>
        tc.state match {
          case ToolCallState.Complete(content, images) if content.length > frameElisionThreshold =>
            sigil.withDB(_.eventsTransaction(conversationId)(_.get(tc.sourceEventId))).map { evOpt =>
              val authored = evOpt.collect {
                case ti: _root_.sigil.event.ToolInvoke if ti.summary.trim.nonEmpty => ti.summary.trim
              }
              val gist = authored.getOrElse(headExcerpt(content))
              tc.copy(state = ToolCallState.Complete(
                elisionText(tc.toolName.value, gist, content.length, images.size, tc.sourceEventId),
                Nil))
            }
          case _ => Task.pure(tc)
        }
      case t: ContextFrame.Text if t.content.length > frameElisionThreshold =>
        Task.pure(t.copy(content =
          elisionText("message", headExcerpt(t.content), t.content.length, 0, t.sourceEventId)))
      case other => Task.pure(other)
    })

  private def headExcerpt(s: String): String = s.take(frameElisionHeadChars).trim

  private def elisionText(label: String,
                          gist: String,
                          fullLen: Int,
                          imageCount: Int,
                          eventId: Id[_root_.sigil.event.Event]): String = {
    val imgs = if (imageCount > 0) s", $imageCount image(s)" else ""
    s"$gist… [$label content elided to fit the context budget — $fullLen chars$imgs. " +
      s"Reload full content with reload_content(\"${eventId.value}\").]"
  }

  /**
   * Resolve the criticalMemories / memories id buckets from a
   * [[MemoryRetrievalResult]] to full records via the DB.
   */
  private def resolveMemoriesAndSummaries(memResult: MemoryRetrievalResult): Task[(Vector[ContextMemory], Vector[ContextMemory])] = {
    val now = lightdb.time.Timestamp()
    // Sigil bug #170 — both id buckets share one memories transaction.
    // Previously each id opened its own RocksDB snapshot; on a turn
    // with ~32 imported memories in scope the per-id setup cost added
    // seconds to "Resolving token budget."
    sigil.withDB(_.memories.transaction { tx =>
      for {
        crit <- Task.sequence(memResult.criticalMemories.toList.map(tx.get))
        regular <- Task.sequence(memResult.memories.toList.map(tx.get))
      } yield (
        crit.flatten.iterator.filterNot(StandardMemoryRetriever.isExpired(_, now)).toVector,
        regular.flatten.iterator.filterNot(StandardMemoryRetriever.isExpired(_, now)).toVector
      )
    })
  }

  /**
   * Information ids referenced inside the current frames.
   */
  private def referencedInformationIds(frames: Vector[ContextFrame]): Set[String] = {
    val needle = "Information["
    frames.iterator.flatMap {
      case t: ContextFrame.Text => extractIds(t.content, needle)
      case tc: ContextFrame.ToolCall =>
        // Sigil #261 — unified frame: args + (if Complete) result content.
        val argIds = extractIds(tc.argsJson, needle)
        val resultIds = tc.state match {
          case ToolCallState.Complete(content, _) => extractIds(content, needle)
          case ToolCallState.Active => Iterator.empty
        }
        argIds ++ resultIds
      case s: ContextFrame.System => extractIds(s.content, needle)
      case _ => Iterator.empty
    }.toSet
  }

  private[compression] def extractIds(content: String, needle: String): Iterator[String] =
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
        val topRender = top3.map { case (k, n) => s"$k @$n tok" }.mkString(", ")
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
    if (memResult.criticalMemories.isEmpty) Task.pure(Vector.empty)
    else sigil.withDB(_.memories.transaction { tx =>
      // Sigil bug #170 — one transaction, N gets.
      Task.sequence(memResult.criticalMemories.toList.map(tx.get)).map(_.flatten.toVector)
    })

  private def modelFor(modelId: Id[Model]): Task[Option[Model]] =
    Task.pure(sigil.cache.find(modelId))

  /**
   * Run [[paraphraseDetector]] over the turn's frame history; on a
   * hit, append the observation to `extraContext` under
   * [[ParaphraseLoopDetector.ContextKeyValue]]. No-op when the
   * detector is not configured or the chain has no agent
   * participant the detector can scope to.
   */
  private def injectParaphraseObservation(turn: TurnInput, chain: List[ParticipantId]): TurnInput =
    paraphraseDetector match {
      case None => turn
      case Some(detector) =>
        chain.lastOption match {
          case None => turn
          case Some(agentId) =>
            detector.detect(turn.frames, agentId) match {
              case None => turn
              case Some(pattern) =>
                turn.copy(extraContext = turn.extraContext +
                  (_root_.sigil.conversation.ContextKey(ParaphraseLoopDetector.ContextKeyValue) -> pattern.render()))
            }
        }
    }
}

object StandardContextCurator {

  /**
   * Sigil #288 — replace oversized top-level string fields in a
   * tool-call args JSON with a short placeholder. The placeholder
   * keeps the wire type intact (string → string) and conveys the
   * original size + truncation marker so the model can recognise
   * that the framework elided it. Same-string identity is preserved
   * when no rewrite fires so callers can `eq`-check for "nothing
   * changed."
   *
   * Only top-level string-valued fields with names in `fields` are
   * candidates. Object / array / numeric fields pass through
   * untouched even if their byte size exceeds the threshold — this
   * pass is targeted at the "agent shipped a large prose / file body
   * as a tool arg" pattern, not general-purpose JSON walking.
   */
  def rewriteOversizedFields(argsJson: String, fields: Set[String], threshold: Long): String = {
    import fabric.{Json, Obj, obj, str}
    import fabric.io.{JsonFormatter, JsonParser}
    if (fields.isEmpty || argsJson.length <= threshold) return argsJson
    val parsed = scala.util.Try(JsonParser(argsJson)).toOption.collect { case o: Obj => o }
    parsed match {
      case None => argsJson
      case Some(o) =>
        val original = o.value
        var changed = false
        val rewritten = original.map { case (k, v) =>
          if (fields.contains(k)) v match {
            case s: fabric.Str if s.value.length.toLong > threshold =>
              changed = true
              val size = s.value.length
              k -> str(s"[externalized — $size chars elided; original in event log, " +
                "recoverable via search_conversation]")
            case _ => k -> v
          }
          else k -> v
        }
        if (!changed) argsJson else JsonFormatter.Compact(obj(rewritten.toSeq*))
    }
  }

  /**
   * Sigil #289 — keep only the most-recent `keepRecent`
   * image-bearing [[sigil.conversation.ContextFrame.ToolCall]]
   * frames; replace older frames' `state` with empty `images` plus
   * a short text stub explaining the suppression. The durable
   * event log is untouched (frames are derived from settled events
   * each turn); subsequent curates see the same elision until the
   * underlying events fall out of `maxFramesPerTurn`.
   *
   * `keepRecent = Int.MaxValue` disables supersession entirely
   * (every image-bearing frame keeps its `images`). `keepRecent =
   * 0` is the most aggressive — even the latest image stubs out;
   * apps wire this for low-multimodal-budget scenarios where the
   * agent should never re-see prior images.
   *
   * Only `ToolCallState.Complete` frames with a non-empty `images`
   * list are candidates; `Active` frames and Complete frames with
   * empty `images` pass through untouched.
   */
  def supersedeOlderImages(frames: Vector[_root_.sigil.conversation.ContextFrame],
                           keepRecent: Int): Vector[_root_.sigil.conversation.ContextFrame] = {
    if (keepRecent == Int.MaxValue) return frames
    val keep = math.max(0, keepRecent)
    // Index the image-bearing frame positions.
    val imageIndices = frames.iterator.zipWithIndex.collect {
      case (tc: _root_.sigil.conversation.ContextFrame.ToolCall, idx) =>
        tc.state match {
          case _root_.sigil.conversation.ToolCallState.Complete(_, images) if images.nonEmpty => Some(idx)
          case _ => None
        }
    }.flatten.toVector
    if (imageIndices.size <= keep) return frames
    val supersededSet = imageIndices.dropRight(keep).toSet
    frames.zipWithIndex.map {
      case (tc: _root_.sigil.conversation.ContextFrame.ToolCall, idx) if supersededSet.contains(idx) =>
        tc.state match {
          case _root_.sigil.conversation.ToolCallState.Complete(_, _) =>
            val stub = s"[image suppressed for context budget — produced by ${tc.toolName.value}; " +
              "recoverable via search_conversation against the conversation event log]"
            tc.copy(state = _root_.sigil.conversation.ToolCallState.Complete(content = stub, images = Nil))
          case _ => tc
        }
      case (other, _) => other
    }
  }
}
