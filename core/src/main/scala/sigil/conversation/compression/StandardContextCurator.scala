package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, ParticipantProjection, ToolCallState, TurnInput}
import sigil.db.Model
import sigil.diagnostics.ProfileSection
import sigil.information.InformationSummary
import sigil.orchestrator.Directive
import sigil.provider.{ContextSection, ContextSections}

import scala.annotation.tailrec
import sigil.participant.ParticipantId
import sigil.conversation.compression.extract.{MemoryExtractor, NoOpMemoryExtractor}
import sigil.tokenize.{HeuristicTokenizer, JtokkitTokenizer, Tokenizer}
import sigil.tool.Freshness

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
 *        - The section-shaped stages, in
 *          [[sigil.provider.ContextSection.shedStage]] order — each
 *          section's own `shed` effect, so the framework's (retrieved
 *          memories, then unreferenced Information, then summaries) and
 *          an app's run on the same terms.
 *        - Then the structural stages: frame elision, escalation, and
 *          iterative frame compression.
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
                                    * shed. Defaults to the in-memory BPE tokenizer
                                    * ([[sigil.tokenize.JtokkitTokenizer.OpenAIO200k]],
                                    * which self-degrades to [[HeuristicTokenizer]] when
                                    * the jtokkit dependency is absent). Real BPE counts
                                    * matter here: on markup-heavy content (CSS / HTML /
                                    * Liquid) the character heuristic UNDER-counts by
                                    * 20-30%, which pushed the effective trigger of the
                                    * default `Percentage(0.8)` budget past the model's
                                    * wire window — compression mathematically never ran
                                    * before the provider hard-rejected the request. A
                                    * network-backed `tokenizer` (`LlamaCppTokenizer`
                                    * etc.) must NOT be plugged here — budget math runs
                                    * over the full frame vector and re-runs on the
                                    * survivors of every shed stage, so a per-text HTTP
                                    * round-trip means multi-minute hangs on bulk-imported
                                    * histories. [[HeuristicTokenizer]] remains a valid
                                    * explicit choice when jtokkit's per-frame encoding
                                    * cost matters more than gate accuracy. */
                                  budgetTokenizer: Tokenizer = JtokkitTokenizer.OpenAIO200k,
                                  /** Frames whose rendered content exceeds this elide
                                    * to gist+reload-id under budget pressure (stage
                                    * 2c). High enough that ordinary messages pass
                                    * through untouched; low enough that a bulk tool
                                    * result or a giant paste is caught. Consumers
                                    * doing code / markup work (where 2K chars is a
                                    * modest read) tune upward. */
                                  frameElisionThreshold: Int = 2000,
                                  /** Characters of the original content kept as the
                                    * elision gist when the tool author supplied no
                                    * summary. */
                                  frameElisionHeadChars: Int = 240,
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
                                    * `semantic_search` / persisted summaries —
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
        .collect { case t if t.freshness.contains(Freshness.Volatile) => t.name.value }
        .toSet
      for {
        _             <- control.step("Loading frames")
        // Load persisted summaries first so the per-turn frame
        // filter can skip events that an intra-turn summary
        // (sigil #285) already subsumes. Without this ordering the
        // frame slice would carry both the originals AND the
        // summary text on every subsequent turn.
        allSummaries  <- if (loadPersistedSummaries) sigil.summariesFor(conversationId)
                         else Task.pure(List.empty[ContextSummary])
        // Summaries carry Frames-style content (a growing history) and
        // must live under Frames-style governance. Two stages:
        //
        //   1. Subsumption filter — a summary whose coverage is a
        //      subset of another's is a stale restatement (the rolling
        //      compaction stream now supersedes at write, but this
        //      retro-cleans append logs already in consumers' stores
        //      and guards any other writer).
        //   2. Token budget — newest-first cumulative `tokenEstimate`
        //      up to [[sigil.Sigil.summariesTokenBudget]]; older
        //      summaries beyond it drop from the prompt. The section
        //      PLATEAUS instead of growing unbounded (measured live:
        //      1.7K → 22K tokens across one turn, 40% of late context).
        selectedSummaries = StandardContextCurator.selectSummaries(allSummaries, sigil.summariesTokenBudget)
        // Build the elision set across EVERY summary's coversEventIds —
        // including budget-dropped and subsumed ones: their covered
        // frames must not resurface as raw history (that would regrow
        // the frame section); the content stays durable and reachable
        // via search / reload_content. Empty (the common case) means no
        // per-event filtering kicks in and frames flow through as before.
        elidedEvents: Set[Id[_root_.sigil.event.Event]] =
          allSummaries.iterator.flatMap(_.coversEventIds).toSet
        rawFrames     <- sigil.framesFor(conversationId)
        visibleFrames0 = rawFrames.filter(f =>
          sigil.visibilityAllows(f.visibility, chain.lastOption.orNull) &&
            !elidedEvents.contains(f.sourceEventId)
        )
        // Sigil #385 — shed consumed framework-internal diagnostic frames
        // (keeping the newest nudge, plus every durable directive) so
        // stall/refusal/cap nudges don't pile up in the prompt and re-feed
        // the loop they were meant to break.
        visibleFrames = StandardContextCurator.dropStaleInternalFrames(visibleFrames0)
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
        // Sigil #288 — rewrite ContextFrame.ToolCall.argsJson to
        // truncate fields the tool opts into externalization. The
        // durable event log is untouched; only the per-turn prompt
        // shrinks. The agent recovers original payloads via
        // `search_conversation` if needed.
        externalizedFrames <- externalizeToolUseFields(sigil, blockResult.frames)
        // Sigil #382 — the recency-based image stubbing (#289) is gone.
        // It evicted by recency (a bad proxy for relevance), destroyed the
        // derived knowledge on eviction (manufacturing `view_file`
        // re-acquisition loops), and mutated the cached prefix every turn
        // (cache-write blowup). Image frames now stay STABLE in the
        // prefix; the only backstop is the pressure-triggered,
        // oldest-first, CAPTION-preserving eviction inside `budgetResolve`
        // (which keeps each evicted frame's text so the agent never has to
        // re-fetch what it already saw).
        externalizedBlock   = blockResult.copy(frames = externalizedFrames)
        _             <- control.step("Retrieving memories")
        // Memory retrieval is an enrichment, not a precondition: a
        // Lucene / vector / embedding hiccup degrades the turn to
        // "no memories surfaced", it never kills it.
        memoryResult  <- memoryRetriever.retrieve(sigil, conversationId, externalizedBlock.frames, chain)
                           .handleError { e =>
                             Task {
                               scribe.warn(s"StandardContextCurator: memory retrieval failed for " +
                                 s"${conversationId.value} (${e.getClass.getSimpleName}: ${e.getMessage}) — continuing without memories")
                               MemoryRetrievalResult(memories = Vector.empty, criticalMemories = Vector.empty)
                             }
                           }
        // Pull persisted summaries — compression-time records from
        // earlier turns + any narrative summaries an app's UX
        // generated explicitly via `MemoryContextCompressor.compressHierarchical`.
        // Older history is represented this way without re-occupying
        // the raw-frame stream every turn.
        persistedSummaries =
          if (loadPersistedSummaries) selectedSummaries.map(_._id).toVector
          else Vector.empty
        projections   <- loadProjections(conversationId, chain)
        tentative     = injectParaphraseObservation(
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
        modelOpt      <- modelFor(modelId)
        _             <- control.step("Resolving token budget")
        shed          <- modelOpt match {
          case Some(model) =>
            budgetResolve(model, tentative, modelId, chain, memoryResult, externalizedBlock.information)
          case None =>
            Task.pure(tentative)
        }
        result        <- modelOpt match {
          case Some(model) => attachBudgetWarning(shed, model, memoryResult, modelId, chain, conversationId)
          case None        => Task.pure(shed)
        }
      } yield result
    }

  /** Sigil #100 — re-fit an already-curated [[TurnInput]] to a
    * (typically smaller) served model's window by re-running the SAME
    * budget gate `curate` ends with, just against the served model's
    * `contextLength`. No new reduction path: it replays
    * [[budgetResolve]] — caption-preserving image eviction, then
    * dropping only recoverable retrieved memories / Information /
    * summaries, then eliding oversized frames to `reload_content`
    * pointers, with lossy frame summarization only as a last resort —
    * so down-sizing inherits the non-lossy-first cascade and never
    * touches pinned (critical) memories. Reconstructs the resolver
    * inputs from the TurnInput itself (its already-retrieved memory /
    * information ids). No-op when the model isn't registered. */
  override def refit(turnInput: TurnInput,
                     modelId: Id[Model],
                     chain: List[ParticipantId]): Task[TurnInput] =
    refit(turnInput, modelId, chain, capOverride = None)

  /** [[refit]] with an explicit token cap in place of
    * `budget.tokensFor(model)`. The agent loop's context-overflow
    * recovery passes an emergency cap well under the wire window after
    * the provider has hard-rejected a request — the wire's rejection is
    * ground truth that the estimator under-counted, so the emergency
    * cap must not be derived from the same estimate that just failed. */
  def refit(turnInput: TurnInput,
            modelId: Id[Model],
            chain: List[ParticipantId],
            capOverride: Option[Int]): Task[TurnInput] =
    modelFor(modelId).flatMap {
      case Some(model) =>
        budgetResolve(
          model = model,
          tentative = turnInput,
          modelId = modelId,
          chain = chain,
          memoryResult = MemoryRetrievalResult(
            memories = turnInput.memories,
            criticalMemories = turnInput.criticalMemories
          ),
          information = turnInput.information,
          capOverride = capOverride
        )
      case None => Task.pure(turnInput)
    }

  /** Sigil #288 — rewrite ContextFrame.ToolCall.argsJson values for
    * tool-opted-in fields that exceed [[Sigil.inlineToolUseContentThreshold]].
    * Resolves each distinct toolName via [[Sigil.findTools]] once per
    * curate call; per-frame walk just applies the cached opt-in set.
    *
    * Only `ToolCallState.Complete` frames externalize — `Active` frames
    * are mid-turn debug projections where the agent might still be
    * processing the in-flight tool_use; we don't truncate those. */
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
                            information: Vector[InformationSummary],
                            capOverride: Option[Int] = None): Task[TurnInput] =
    for {
      memTuple <- resolveMemoriesAndSummaries(memoryResult)
      resolvedSummaries <- resolveSummaries(tentative.summaries)
      out <- {
        val (resolvedCritical, resolvedRetrieved) = memTuple
        val cap = capOverride.getOrElse(budget.tokensFor(model))

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

        val frames0 = tentative.frames
        // Sigil #382 — pressure-triggered, oldest-first, caption-preserving
        // image eviction. When over budget, drop the oldest image frames'
        // pixels (keeping their captions, so the agent never re-fetches what
        // it already saw) up front; the rest of the shed cascade then runs
        // on the result. Minimally lossy — captions are preserved, and on a
        // fitting conversation nothing is touched (stable cached prefix).
        val frames =
          if (tokensOf(tentative, frames0, resolvedSummaries) <= cap) frames0
          else StandardContextCurator.evictImagesToFit(
            frames0, candidate => tokensOf(tentative, candidate, resolvedSummaries) <= cap)
        val tent = tentative.copy(frames = frames)

        // Sigil #416 — stamp the returned TurnInput when this curate had
        // to elide frame content so downstream consumers (the naked-text
        // decision challenge, apps) can tell a pressured turn apart from
        // a normal one.
        def stampPressure(t: TurnInput, elidedCount: Int): TurnInput =
          if (elidedCount <= 0) t
          else t.copy(extraContext = t.extraContext +
            (StandardContextCurator.ContextPressureKey ->
              s"$elidedCount frame(s) elided to fit the context budget this turn"))

        // Sigil #416 — final backstop applied after the full cascade: if
        // the result STILL exceeds the cap (active-turn protection can
        // hold a lot of content; shed floors at keepMinimum), re-run the
        // elision with NO exemptions. Starving the active turn is the
        // bounded worst case — better than shipping a request the
        // provider will hard-reject.
        def fitOrLastResort(result: TurnInput): Task[TurnInput] =
          if (tokensOf(result, result.frames, Vector.empty) <= cap) Task.pure(result)
          else compactLargeFrames(tentative.conversationId, result.frames, Set.empty).map { fullyElided =>
            stampPressure(result.copy(frames = fullyElided), 1)
          }

        /** The section-shaped shed stages, in
          * [[sigil.provider.ContextSection.shedStage]] order — each
          * section's own `shed` effect applied to the turn, re-gated
          * against the cap before the next runs. Returns `Right` at the
          * first stage that fits, `Left` with everything shed when none
          * does. Framework sections shed retrieved memories, then
          * unreferenced Information, then persisted summaries; an app's
          * sections take part on the same terms. */
        @tailrec
        def sectionShedCascade(remaining: List[ContextSection],
                               current: TurnInput,
                               summariesArg: Vector[ContextSummary]): Either[TurnInput, TurnInput] =
          remaining match {
            case Nil             => Left(current)
            case section :: rest =>
              val next = section.shed.fold(current)(_(current))
              val nextSummaries = if (next.summaries.isEmpty) Vector.empty else summariesArg
              if (tokensOf(next, frames, nextSummaries) <= cap) Right(next)
              else sectionShedCascade(rest, next, nextSummaries)
          }

        if (tokensOf(tent, frames, resolvedSummaries) <= cap) Task {
          sigil.elisionPressureStreaks.remove(tentative.conversationId)
          tent
        }
        else {
          sectionShedCascade(ContextSections.shedCascade(sigil.contextSections), tent, resolvedSummaries) match {
            case Right(fitted)            => Task.pure(fitted)
            case Left(afterSectionSheds)  =>
              resolveElisionProtectedIds(tentative.conversationId, frames).flatMap { elisionProtected =>
                compactLargeFrames(tentative.conversationId, frames, elisionProtected).flatMap { compacted =>
                // Stage 2c — elide oversized tool-result / message frames
                // to a short summary + reload-id (#316). Targets the
                // actual budget bloat (a giant grep result, a huge
                // message) WITHOUT dropping any frame; full content stays
                // durable and re-examinable via reload_content(eventId). This
                // runs before the lossy frame shed, so the common case
                // (one oversized tool result) never reaches Stage 3.
                //
                // Sigil #416 — active-turn frames are exempt (the agent
                // must be able to act on what it just read), and chronic
                // elision escalates: 2c's relief is per-curate and
                // ephemeral, so a conversation that needs it every turn
                // would re-elide forever while stage 3's DURABLE shed
                // (summary + clearedAt advance) never runs. After
                // `elisionPressureEscalationStreak` consecutive eliding
                // curates, proceed into stage 3 against the UNELIDED
                // frames so history durably shrinks and elision stops
                // being needed.
                val elidedCount = frames.iterator.zip(compacted.iterator).count { case (a, b) => a ne b }
                val streak: Int =
                  if (elidedCount > 0)
                    sigil.elisionPressureStreaks
                      .merge(tentative.conversationId, Int.box(1), (a, b) => Int.box(a.intValue + b.intValue))
                      .intValue
                  else {
                    sigil.elisionPressureStreaks.remove(tentative.conversationId)
                    0
                  }
                // Escalation only helps when a real compressor is wired —
                // the durable shed drops frames strictly via summary, so
                // with NoOpContextCompressor an escalated stage 3 would
                // no-op into the last-resort full elision instead of
                // shrinking anything.
                val escalate = sigil.elisionPressureEscalationStreak > 0 &&
                  streak >= sigil.elisionPressureEscalationStreak &&
                  (compressor ne NoOpContextCompressor)
                val afterStage2c = stampPressure(afterSectionSheds.copy(frames = compacted), elidedCount)
                if (!escalate && tokensOf(afterStage2c, compacted, Vector.empty) <= cap) Task.pure(afterStage2c)
                else {
                // Stage 3 — last-resort frame shed for sheer history
                // length. Resolve the invariant-protected
                // `sourceEventId`s once so the shed never folds the user
                // task or cleaves a paired tool exchange. Under #416
                // escalation the shed runs against the UNELIDED frames
                // (targeting the raw content the per-curate elision keeps
                // rewriting) so the resulting summary + clearedAt advance
                // durably removes it.
                val stage3Frames = if (escalate) frames else compacted
                val stage3Base   = if (escalate) afterSectionSheds else afterStage2c
                resolveProtectedEventIds(tentative.conversationId, stage3Frames).flatMap { protectedIds =>
                  shedFramesIteratively(
                    kept = stage3Frames,
                    droppedSoFar = Vector.empty,
                    summaryCarry = None,
                    cap = cap,
                    modelId = modelId,
                    chain = chain,
                    conversationId = tentative.conversationId,
                    protectedSourceEventIds = protectedIds,
                    tokensOfKept = (kept, summaryOpt) =>
                      tokensOf(stage3Base, kept, summaryOpt.toVector)
                  )
                }.flatMap { case (newerKept, summaryOpt) =>
                  if (escalate) sigil.elisionPressureStreaks.remove(tentative.conversationId)
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
                      val shedSlice = stage3Frames.dropRight(newerKept.size)
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
                      advance.flatMap(_ => fitOrLastResort(stage3Base.copy(
                        frames    = newerKept,
                        summaries = Vector(summary._id)
                      )))
                    case None =>
                      fitOrLastResort(stage3Base.copy(frames = newerKept))
                  }
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
    else sigil.withDB(_.summaries.transaction { tx =>
      // Sigil bug #170 — N gets share one transaction. Transaction
      // setup is the dominant cost (RocksDB snapshot + iterator);
      // amortising it across the id list collapses an N-step wait
      // into a single setup pair.
      Task.sequence(ids.toList.map(tx.get)).map(_.flatten.toVector)
    })

  /** Iterative Stage 3 shed (bug #23 — preserves the iteration model
    * inside the new bug-#26 architecture). Each pass either fits, hits
    * `keepMinimum`, or falls through on a compressor refusal. When the
    * input exceeds `cap × 3`, jump straight to the floor for a single
    * aggressive collapse instead of rounds of halving.
    *
    * `protectedSourceEventIds` is the union of every
    * [[CompactionInvariant]] applicable to the slice's events. The
    * split point is adjusted forward (older direction) so no
    * protected event lands in the `older` half; protects paired tool
    * exchanges and structurally load-bearing events from being folded. */
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

  /** Walk the split point earlier until no protected frame lands in
    * the `older` half. Returns 0 when every preceding frame is
    * protected (the shed becomes a no-op for this iteration). */
  private def adjustSplitForInvariants(frames: Vector[ContextFrame],
                                       initialSplit: Int,
                                       protectedIds: Set[Id[_root_.sigil.event.Event]]): Int = {
    if (protectedIds.isEmpty) initialSplit
    else {
      var s = initialSplit
      while (s > 0 && protectedIds.contains(frames(s - 1).sourceEventId)) s -= 1
      s
    }
  }

  /** Load the events backing `frames`, run every
    * [[CompactionInvariant]] in [[sigil.Sigil.compactionInvariants]]
    * against the result, and return the union of protected
    * `sourceEventId`s. Best-effort: a DB hiccup or a missing event
    * row degrades to an empty set so the shed still makes progress. */
  private def resolveProtectedEventIds(conversationId: Id[Conversation],
                                        frames: Vector[ContextFrame]): Task[Set[Id[_root_.sigil.event.Event]]] =
    resolveInvariantIds(conversationId, frames, sigil.compactionInvariants)

  /** Event ids stage 2c's frame elision must not touch: the app's
    * configured invariants PLUS the active turn
    * ([[CompactionInvariant.ActiveTurnEvents]]). */
  private def resolveElisionProtectedIds(conversationId: Id[Conversation],
                                         frames: Vector[ContextFrame]): Task[Set[Id[_root_.sigil.event.Event]]] =
    resolveInvariantIds(conversationId, frames,
      sigil.compactionInvariants :+ CompactionInvariant.ActiveTurnEvents)

  private def resolveInvariantIds(conversationId: Id[Conversation],
                                  frames: Vector[ContextFrame],
                                  invariants: List[CompactionInvariant]): Task[Set[Id[_root_.sigil.event.Event]]] = {
    if (invariants.isEmpty || frames.isEmpty) Task.pure(Set.empty)
    else {
      val ids = frames.map(_.sourceEventId).distinct
      sigil.withDB(_.eventsTransaction(conversationId) { tx =>
        Task.sequence(ids.toList.map(tx.get)).map(_.flatten.toVector)
      }).map { events =>
        val sorted = events.sortBy(_.timestamp.value)
        val ctx = TurnEventsContext(
          conversationId = conversationId,
          deliveredToolResults = sigil.deliveredToolResultIds(conversationId)
        )
        invariants.iterator.flatMap(_.applicableIds(sorted, ctx)).toSet
      }.handleError(_ => Task.pure(Set.empty))
    }
  }

  /** #316 — per-frame budget elision. Replace oversized tool-result and
    * message frame content with a short gist + a `reload_content(eventId)`
    * reload pointer, keeping the frame in place. Non-destructive: the
    * durable event retains full content, re-examinable via reload_content.
    * Prefers the tool author's `ToolInvoke.summary` for the gist (this
    * runs only on the over-budget path, over oversized frames, so the
    * per-frame event lookup is rare), falling back to a head excerpt.
    *
    * `protectedIds` (typically the [[CompactionInvariant.ActiveTurnEvents]]
    * resolution) are exempt: the agent must be able to act on what it
    * just read — a this-turn tool result rewritten to a stub between
    * iterations starves the work in progress. The last-resort pass in
    * `budgetResolve` re-runs with an empty set when protection alone
    * can't fit the cap. */
  private def compactLargeFrames(conversationId: Id[Conversation],
                                 frames: Vector[ContextFrame],
                                 protectedIds: Set[Id[_root_.sigil.event.Event]]): Task[Vector[ContextFrame]] =
    Task.sequence(frames.map {
      case f if protectedIds.contains(f.sourceEventId) => Task.pure(f)
      case tc: ContextFrame.ToolCall =>
        tc.state match {
          case ToolCallState.Complete(content, images) if content.length > frameElisionThreshold =>
            sigil.withDB(_.eventsTransaction(conversationId)(_.get(tc.sourceEventId))).map { evOpt =>
              val authored = evOpt.collect {
                case ti: _root_.sigil.event.ToolInvoke if ti.summary.trim.nonEmpty => ti.summary.trim
              }
              val gist = authored.getOrElse(headExcerpt(content))
              tc.copy(state = ToolCallState.Complete(
                elisionText(tc.toolName.value, gist, content.length, images.size, tc.sourceEventId), Nil))
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

  /** Resolve the criticalMemories / memories id buckets from a
    * [[MemoryRetrievalResult]] to full records via the DB. The ids may
    * come from a cached retrieval computed earlier in the burst, so
    * the full recall gate ([[ContextMemory.isRecallable]]) re-applies
    * here — a memory rejected or superseded mid-burst stops
    * contributing to the budget as well as to the prompt. */
  private def resolveMemoriesAndSummaries(memResult: MemoryRetrievalResult): Task[(Vector[ContextMemory], Vector[ContextMemory])] = {
    val now = lightdb.time.Timestamp()
    // Sigil bug #170 — both id buckets share one memories transaction.
    // Previously each id opened its own RocksDB snapshot; on a turn
    // with ~32 imported memories in scope the per-id setup cost added
    // seconds to "Resolving token budget."
    sigil.withDB(_.memories.transaction { tx =>
      for {
        crit    <- Task.sequence(memResult.criticalMemories.toList.map(tx.get))
        regular <- Task.sequence(memResult.memories.toList.map(tx.get))
      } yield (
        crit.flatten.iterator.filter(_.isRecallable(now)).toVector,
        regular.flatten.iterator.filter(_.isRecallable(now)).toVector
      )
    })
  }


  private def attachBudgetWarning(turnInput: TurnInput,
                                  model: Model,
                                  memResult: MemoryRetrievalResult,
                                  modelId: Id[Model],
                                  chain: List[ParticipantId],
                                  conversationId: Id[Conversation]): Task[TurnInput] =
    if (memResult.criticalMemories.isEmpty) Task.pure(turnInput)
    else resolveCriticalForWarning(memResult).flatMap { pinnedMemories =>
      val pinnedTokens =
        TokenEstimator.estimateMemories(pinnedMemories, tokenizer, ContextSections.CriticalMemoriesHeader)
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
          turnInput.copy(extraContext = turnInput.extraContext + (ContextKey.BudgetWarning -> message))
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
                  (_root_.sigil.conversation.ContextKey.ParaphraseObservation -> pattern.render()))
            }
        }
    }
}

object StandardContextCurator {

  /** Information ids referenced inside the current frames. */
  private[sigil] def referencedInformationIds(frames: Vector[ContextFrame]): Set[String] = {
    val needle = "Information["
    frames.iterator.flatMap {
      case t: ContextFrame.Text     => extractIds(t.content, needle)
      case tc: ContextFrame.ToolCall =>
        // Sigil #261 — unified frame: args + (if Complete) result content.
        val argIds = extractIds(tc.argsJson, needle)
        val resultIds = tc.state match {
          case ToolCallState.Complete(content, _) => extractIds(content, needle)
          case ToolCallState.Active               => Iterator.empty
        }
        argIds ++ resultIds
      case s: ContextFrame.System   => extractIds(s.content, needle)
      case _                        => Iterator.empty
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

  /** Frames-style governance for the Summaries section: drop
    * subsumed restatements, then budget newest-first by
    * `tokenEstimate`. Returns the kept summaries oldest-first (their
    * render order). */
  def selectSummaries(all: List[_root_.sigil.conversation.ContextSummary],
                      tokenBudget: Int): List[_root_.sigil.conversation.ContextSummary] = {
    val nonSubsumed = all.filterNot { s =>
      s.coversEventIds.nonEmpty && all.exists { other =>
        other._id != s._id &&
          other.coversEventIds.size >= s.coversEventIds.size &&
          s.coversEventIds.forall(other.coversEventIds.toSet.contains) &&
          // Equal coverage: keep the newer record, drop the older.
          (other.coversEventIds.size > s.coversEventIds.size || other.created.value > s.created.value)
      }
    }
    val newestFirst = nonSubsumed.sortBy(-_.created.value)
    val kept = scala.collection.mutable.ListBuffer.empty[_root_.sigil.conversation.ContextSummary]
    var spent = 0L
    newestFirst.foreach { s =>
      val cost = math.max(1, s.tokenEstimate).toLong
      // Always keep at least the newest summary, even if it alone
      // exceeds the budget — an empty section would orphan the
      // elided frames entirely.
      if (kept.isEmpty || spent + cost <= tokenBudget) {
        kept += s
        spent += cost
      }
    }
    kept.toList.sortBy(_.created.value)
  }

  /** Sigil #416 — [[sigil.conversation.TurnInput.extraContext]] key
    * stamped by `budgetResolve` when stage 2c had to elide frame
    * content this curate. Downstream consumers read it to distinguish
    * a pressured turn from a normal one — notably the orchestrator's
    * naked-text decision challenge, which backs off rather than
    * prodding a context-starved agent through extra iterations. */
  val ContextPressureKey: _root_.sigil.conversation.ContextKey =
    _root_.sigil.conversation.ContextKey.ContextPressure

  /** Sigil #288 — replace oversized top-level string fields in a
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
    * as a tool arg" pattern, not general-purpose JSON walking. */
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

  /** Sigil #382 — knowledge-preserving image eviction. Drop the
    * `images` (pixels) of the OLDEST `evictCount` image-bearing
    * [[ContextFrame.ToolCall]] frames while KEEPING each frame's text —
    * which is the image's caption ([[sigil.conversation.FrameBuilder]]
    * already folds the tool's `text`/`alt`/summary into the frame
    * content). The agent reads what the image showed rather than being
    * forced to re-fetch it (the loop the recency-stubbing #289
    * manufactured). Pressure-triggered from [[budgetResolve]] only;
    * image frames are otherwise left STABLE so the cached prefix doesn't
    * churn. Returns `frames` unchanged when `evictCount <= 0`.
    *
    * Only `ToolCallState.Complete` frames with a non-empty `images` list
    * are candidates. */
  def evictOldestImages(frames: Vector[ContextFrame], evictCount: Int): Vector[ContextFrame] = {
    if (evictCount <= 0) return frames
    val imageIndices = frames.iterator.zipWithIndex.collect {
      case (tc: ContextFrame.ToolCall, idx) =>
        tc.state match {
          case ToolCallState.Complete(_, images) if images.nonEmpty => Some(idx)
          case _ => None
        }
    }.flatten.toVector
    val evictSet = imageIndices.take(evictCount).toSet // oldest-first
    frames.zipWithIndex.map {
      case (tc: ContextFrame.ToolCall, idx) if evictSet.contains(idx) =>
        tc.state match {
          case ToolCallState.Complete(content, _) =>
            // Keep the caption (content); drop only the pixels.
            tc.copy(state = ToolCallState.Complete(content = content, images = Nil))
          case _ => tc
        }
      case (other, _) => other
    }
  }

  /** Sigil #385 — shed consumed framework-internal diagnostic frames
    * (`ContextFrame.ToolCall(internal = true)`). Transient nudges (stall /
    * refusal-challenge / cap directives) steer ONE next iteration; once
    * consumed, stale copies are pure context noise that, re-sent every turn,
    * compound the very loop they warn against (observed live: a single turn's
    * prompt carried 200+ accumulated `_stall_detected` diagnostics), so only
    * the newest transient frame survives.
    *
    * Durable directives ([[sigil.orchestrator.Directive.durableWireNames]] —
    * the plan) are exempt: the planner keeps judging the executor against a
    * plan the executor must still be able to read. The newest frame per
    * durable wire name is kept, so a replan supersedes its predecessor
    * without a later stall nudge evicting the plan entirely.
    *
    * The durable events are untouched; this is a per-turn prompt-shaping
    * step only. */
  def dropStaleInternalFrames(frames: Vector[ContextFrame]): Vector[ContextFrame] = {
    val internal = frames.iterator.zipWithIndex.collect {
      case (tc: ContextFrame.ToolCall, idx) if tc.internal => tc -> idx
    }.toVector
    if (internal.size <= 1) frames
    else {
      val (durable, transient) = internal.partition {
        case (tc, _) => Directive.durableWireNames.contains(tc.toolName.value)
      }
      val internalIdx = internal.map(_._2).toSet
      val keep = durable.groupBy(_._1.toolName.value).values.map(_.map(_._2).max).toSet ++
        transient.lastOption.map(_._2).toSet
      frames.zipWithIndex.collect {
        case (f, idx) if !internalIdx.contains(idx) || keep.contains(idx) => f
      }
    }
  }

  /** Sigil #382 — progressively evict the oldest image frames' pixels
    * (caption-preserving, via [[evictOldestImages]]) until `fits` holds
    * or no image pixels remain. Returns the fully-evicted frames if it
    * never fits — the residual shed cascade then handles the rest. */
  def evictImagesToFit(frames: Vector[ContextFrame],
                       fits: Vector[ContextFrame] => Boolean): Vector[ContextFrame] = {
    if (fits(frames)) return frames
    val total = imageFrameCount(frames)
    if (total == 0) return frames
    var n = 1
    while (n <= total) {
      val candidate = evictOldestImages(frames, n)
      if (fits(candidate)) return candidate
      n += 1
    }
    evictOldestImages(frames, total)
  }

  /** Number of image-bearing frames still carrying pixels (sigil #382). */
  def imageFrameCount(frames: Vector[ContextFrame]): Int =
    frames.count {
      case tc: ContextFrame.ToolCall =>
        tc.state match {
          case ToolCallState.Complete(_, images) => images.nonEmpty
          case _ => false
        }
      case _ => false
    }
}
