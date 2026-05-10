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
                                  pinnedShareWarningThreshold: Double = 0.20) extends ContextCurator {

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
        optimizedFrames = optimizer.optimize(visibleFrames, elide, chain.headOption)
        _             <- control.step(s"Extracting blocks (${optimizedFrames.size} frames)")
        blockResult   <- blockExtractor.extract(sigil, optimizedFrames)
        _             <- control.step("Retrieving memories")
        memoryResult  <- memoryRetriever.retrieve(sigil, conversationId, blockResult.frames, chain)
        projections   <- loadProjections(conversationId, chain)
        tentative     = TurnInput(
          conversationId = conversationId,
          frames = blockResult.frames,
          participantProjections = projections,
          criticalMemories = memoryResult.criticalMemories,
          memories = memoryResult.memories,
          information = blockResult.information
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

        val frames = tentative.frames

        if (tokensOf(tentative, frames, Vector.empty) <= cap) Task.pure(tentative)
        else {
          // Stage 1 — drop non-critical retrieved memories.
          val afterStage1 = tentative.copy(memories = Vector.empty)
          if (tokensOf(afterStage1, frames, Vector.empty) <= cap) Task.pure(afterStage1)
          else {
            // Stage 2 — drop unreferenced Information.
            val referenced = referencedInformationIds(frames)
            val keptInformation = information.filter(i => referenced.contains(i.id.value))
            val afterStage2 = afterStage1.copy(information = keptInformation)
            if (tokensOf(afterStage2, frames, Vector.empty) <= cap) Task.pure(afterStage2)
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
                  tokensOf(afterStage2, kept, summaryOpt.toVector)
              ).map { case (newerKept, summaryOpt) =>
                summaryOpt match {
                  case Some(summary) =>
                    afterStage2.copy(
                      frames = newerKept,
                      summaries = Vector(summary._id)
                    )
                  case None =>
                    afterStage2.copy(frames = newerKept)
                }
              }
            }
          }
        }
    }

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
}
