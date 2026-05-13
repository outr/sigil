package sigil.conversation.compression

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.{FrameworkWorkflowControl, Sigil}
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, Conversation, MemorySource}
import sigil.SpaceId
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, ReasoningMode, SummarizationWork}
import sigil.tool.consult.{ConsultTool, ExtractMemoriesInput, ExtractMemoriesTool, SummarizationInput, SummarizationTool}

/**
 * Two-pass LLM compressor. First pass asks the consulted model to
 * extract a list of durable facts (via [[ExtractMemoriesTool]]);
 * the framework persists each fact as a [[ContextMemory]] in the space
 * returned by [[sigil.Sigil.compressionMemorySpace]]. Facts that come
 * back with a `key` get versioned via `upsertMemoryByKey`; keyless
 * facts append as new records. Second pass summarizes the excerpt the
 * normal way (via [[SummarizationTool]]), so the summary is free to
 * stay short — the facts it would otherwise repeat are already on disk.
 *
 * This is the compression-time pathway. For per-turn extraction (fire
 * after every agent response, before compression thresholds trigger),
 * see
 * [[sigil.conversation.compression.extract.StandardMemoryExtractor]]
 * which uses the same tool against a smaller window.
 *
 * Falls back to [[SummaryOnlyCompressor]]'s behavior in these cases:
 *   - `compressionMemorySpace(conversationId)` returns `None` (app
 *     hasn't opted into extraction)
 *   - [[extractFacts]] is `false` (knob-disabled)
 *   - the extraction consult call fails or returns no facts
 *
 * Knobs:
 *   - [[extractionSystemPrompt]] / [[summarizationSystemPrompt]] —
 *     prompts for the two passes; defaults emphasize the framework's
 *     semantics (decisions, commitments, identifiers).
 *   - [[renderTranscript]] — shared renderer.
 *   - [[minFactChars]] — facts shorter than this are dropped as
 *     probably-trivial noise.
 *   - [[extractFacts]] — hard disable for the extraction pass (the
 *     compressor collapses to summary-only).
 */
case class MemoryContextCompressor(extractionSystemPrompt: String = MemoryContextCompressor.DefaultExtractionPrompt,
                                   summarizationSystemPrompt: String = SummaryOnlyCompressor.DefaultSystemPrompt,
                                   renderTranscript: SummaryOnlyCompressor.Renderer = SummaryOnlyCompressor.DefaultRenderer,
                                   minFactChars: Int = 10,
                                   extractFacts: Boolean = true,
                                   tokenizer: sigil.tokenize.Tokenizer = sigil.tokenize.HeuristicTokenizer,
                                   reservedOutputTokens: Long = 1024L,
                                   promptOverheadTokens: Long = 512L,
                                   /** Wire-protocol body ceiling shared with the
                                     * chunker. Defaults to [[SummaryOnlyCompressor.DefaultMaxChunkBytes]]
                                     * (8 MB). Bug #143. */
                                   maxChunkBytes: Long = SummaryOnlyCompressor.DefaultMaxChunkBytes,
                                   /** Concurrent leaf-summarization fan-out for
                                     * [[compressHierarchical]]. Default 1 = serial
                                     * (preserves the original behaviour for
                                     * llama.cpp single-slot setups). Apps with
                                     * `--parallel N` configured locally, or
                                     * hosted providers that aren't slot-limited,
                                     * bump this to run leaf chunks concurrently.
                                     * At parallelism = 4 a 100-chunk pass drops
                                     * from ~50 minutes to ~12. Epoch folds run
                                     * with the same knob — fewer calls per
                                     * level, but the same bound applies. Bug
                                     * #145. */
                                   hierarchicalParallelism: Int = 1,
                                   /** Hard cap on the summarisation call's
                                     * generation. Without a cap the model
                                     * falls through to the provider's
                                     * server-side `n_predict` default —
                                     * observed in the wild generating 12K-
                                     * token "summaries" (functionally
                                     * paraphrases of the input) before the
                                     * server truncates mid-sentence. The
                                     * truncated text persisted as a
                                     * `ContextSummary` regardless. 2048
                                     * comfortably fits the two-paragraph
                                     * shape the system prompt asks for;
                                     * chunks that would genuinely need more
                                     * were producing lossy paraphrase
                                     * anyway. Bug #148. */
                                   maxSummaryTokens: Int = 2048,
                                   /** Hard cap on the fact-extraction call's
                                     * generation. Mirror of `maxSummaryTokens`
                                     * for the `extract_memories` path so an
                                     * aggressive extractor can't enumerate
                                     * hundreds of pseudo-facts past any
                                     * useful limit. 1024 holds 20-30 typical
                                     * fact records. Bug #148. */
                                   maxExtractionTokens: Int = 1024) extends ContextCompressor {

  override def compress(sigil: Sigil,
                        callerModelId: Id[Model],
                        chain: List[ParticipantId],
                        frames: Stream[ContextFrame],
                        conversationId: Id[Conversation]): Task[Option[ContextSummary]] =
    sigil.runAsFrameworkWorkflow(
      workflowType = "compress",
      label = "Compressing conversation history",
      conversationId = Some(conversationId)
    ) { control =>
      frames.toList.flatMap { framesList =>
        val materialized = framesList.toVector
        if (materialized.isEmpty) Task.pure(None)
        else for {
          _   <- control.step(s"Routing summarization model (${materialized.size} frames)")
          ctx <- loadContext(sigil, conversationId)
          transcript     = renderTranscript(materialized, ctx._1, ctx._2)
          estimatedInput = (tokenizer.count(transcript) +
                              tokenizer.count(summarizationSystemPrompt) +
                              tokenizer.count(extractionSystemPrompt) +
                              promptOverheadTokens).toLong
          summarizationModel <- sigil.routedModelFor(
                                  SummarizationWork,
                                  chain,
                                  fallback = callerModelId,
                                  estimatedInputTokens = Some(estimatedInput),
                                  reservedOutputTokens = reservedOutputTokens
                                ).handleError(_ => Task.pure(callerModelId))
          spaceOpt <- if (extractFacts) sigil.compressionMemorySpace(conversationId) else Task.pure(None)
          available        = sigil.cache.find(summarizationModel).map(_.contextLength).getOrElse(0L) - reservedOutputTokens - promptOverheadTokens
          transcriptTokens = tokenizer.count(transcript).toLong
          transcriptBytes  = transcript.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
          // Single-shot only when BOTH the token window accommodates
          // the transcript AND the wire body fits the byte ceiling.
          // Bug #143 — token-only check let 18 MB transcripts through
          // to a 200K-context frontier model.
          fitsSinglePass = (available <= 0L || transcriptTokens <= available) && transcriptBytes <= maxChunkBytes
          _ <- control.step("Extracting facts")
          _ <- spaceOpt match {
                 case Some(space) =>
                   if (fitsSinglePass)
                     extractAndPersist(sigil, summarizationModel, chain, transcript, conversationId, space)
                   else
                     extractAndPersistChunked(sigil, summarizationModel, chain, materialized, ctx, conversationId, space, available)
                 case None => Task.unit
               }
          _ <- control.step("Summarizing transcript")
          summary <- if (fitsSinglePass)
                       summarize(sigil, summarizationModel, chain, transcript, conversationId)
                     else
                       SummaryOnlyCompressor(
                         systemPrompt = summarizationSystemPrompt,
                         renderTranscript = renderTranscript,
                         tokenizer = tokenizer,
                         reservedOutputTokens = reservedOutputTokens,
                         promptOverheadTokens = promptOverheadTokens,
                         maxChunkBytes = maxChunkBytes,
                         maxSummaryTokens = maxSummaryTokens
                       ).compress(sigil, summarizationModel, chain, rapid.Stream.emits(materialized), conversationId)
        } yield summary
      }
    }

  /** Bug #41 — extraction with chunk-and-merge: for inputs bigger than
    * the picked model's window, run `extract_memories` per chunk and
    * concatenate the resulting facts. No final merge step (each fact
    * is independently meaningful; cross-chunk dedup happens via
    * `upsertMemoryByKeyFor`). */
  private def extractAndPersistChunked(sigil: Sigil,
                                       modelId: Id[Model],
                                       chain: List[ParticipantId],
                                       frames: Vector[ContextFrame],
                                       ctx: (Option[_root_.sigil.provider.Mode], Option[_root_.sigil.conversation.TopicEntry]),
                                       conversationId: Id[Conversation],
                                       space: SpaceId,
                                       availableTokens: Long): Task[Unit] = {
    val effectiveTokenBudget = if (availableTokens <= 0L) Long.MaxValue else availableTokens
    val chunks = SummaryOnlyCompressor.chunkByTokensAndBytes(
      frames, ctx, renderTranscript, tokenizer, effectiveTokenBudget, maxChunkBytes
    )
    Task.sequence(chunks.map { chunk =>
      val text = renderTranscript(chunk, ctx._1, ctx._2)
      extractAndPersist(sigil, modelId, chain, text, conversationId, space)
    }).unit
  }

  private def extractAndPersist(sigil: Sigil,
                                modelId: Id[Model],
                                chain: List[ParticipantId],
                                transcript: String,
                                conversationId: Id[Conversation],
                                space: SpaceId): Task[Unit] = {
    val userPrompt =
      s"""Extract durable facts from the following conversation excerpt. Output via the
         |`extract_memories` tool. Supply a `key` for facts that represent a durable
         |identity slot whose value may change over time (so future extractions can version it);
         |omit `key` for one-shot facts.
         |
         |${transcript}""".stripMargin
    ConsultTool.invoke[ExtractMemoriesInput](
      sigil = sigil,
      modelId = modelId,
      chain = chain,
      systemPrompt = extractionSystemPrompt,
      userPrompt = userPrompt,
      tool = ExtractMemoriesTool,
      generationSettings = GenerationSettings(
        maxOutputTokens = Some(maxExtractionTokens),
        reasoningMode = ReasoningMode.Off
      )
    ).flatMap {
      case Some(result) =>
        val kept = result.memories.filter(_.content.trim.length >= minFactChars)
        Task.sequence(kept.map { m =>
          val label = if (m.label.trim.nonEmpty) m.label
                      else MemoryContextCompressor.synthesizeLabel(m.content)
          val mem = ContextMemory(
            fact = m.content,
            label = label,
            summary = m.content,
            source = MemorySource.Compression,
            spaceId = space,
            key = m.key,
            keywords = m.tags.toVector,
            conversationId = Some(conversationId)
          )
          if (m.key.isDefined)
            sigil.upsertMemoryByKeyFor(mem, chain, conversationId).map(_.memory)
          else
            sigil.persistMemoryFor(mem, chain, conversationId)
        }).unit
      case None => Task.unit
    }.handleError { e =>
      Task(scribe.warn(s"MemoryContextCompressor: extraction failed for conversation ${conversationId.value}: ${e.getMessage}"))
    }
  }

  private def summarize(sigil: Sigil,
                        modelId: Id[Model],
                        chain: List[ParticipantId],
                        transcript: String,
                        conversationId: Id[Conversation]): Task[Option[ContextSummary]] = {
    val userPrompt =
      s"""Summarize the following conversation excerpt. Output via the `summarize_conversation` tool.
         |
         |${transcript}""".stripMargin
    ConsultTool.invoke[SummarizationInput](
      sigil = sigil,
      modelId = modelId,
      chain = chain,
      systemPrompt = summarizationSystemPrompt,
      userPrompt = userPrompt,
      tool = SummarizationTool,
      generationSettings = GenerationSettings(
        maxOutputTokens = Some(maxSummaryTokens),
        reasoningMode = ReasoningMode.Off
      )
    ).flatMap {
      case Some(result) if result.summary.trim.nonEmpty =>
        val record = ContextSummary(
          text = result.summary.trim,
          conversationId = conversationId,
          tokenEstimate = math.max(1, result.tokenEstimate)
        )
        sigil.persistSummary(record).map(Some(_))
      case _ => Task.pure(None)
    }.handleError { e =>
      Task {
        scribe.warn(s"MemoryContextCompressor: summarization failed for conversation ${conversationId.value}: ${e.getMessage}")
        None
      }
    }
  }

  private def loadContext(sigil: Sigil, conversationId: Id[Conversation]) =
    sigil.withDB(_.conversations.transaction(_.get(conversationId))).map {
      case Some(conv) => (Some(conv.currentMode), conv.topics.lastOption)
      case None       => (None, None)
    }

  /**
   * Hierarchical compression — produces a narrative tree of summary
   * records for a conversation. Chunks frames by `(tokens, bytes)`
   * budgets, summarises each chunk to a [[ContextSummary]]
   * (persisted via [[sigil.Sigil.persistSummary]]), then optionally
   * summarises runs of summaries into higher-level "epoch"
   * summaries when `depth > 1`. Returns the top-level summary set;
   * every intermediate level also persists so
   * [[sigil.Sigil.summariesFor]] surfaces them.
   *
   * **For agent recall, prefer
   * [[sigil.tool.util.SearchConversationTool]] /
   * [[sigil.tool.util.SemanticSearchTool]].** Those retrieve actual
   * exchanges (the durable event log retains everything) rather
   * than lossy paraphrase, which is almost always what the agent
   * wants. The curator's `maxFramesPerTurn` cap already keeps the
   * per-turn prompt bounded; older content remains reachable via
   * search at the moment a question references it.
   *
   * Call this directly only when the UX requires a precomputed
   * narrative summary (e.g. a "Generate session overview" button,
   * a daily-digest panel, an emailed recap). Apps trigger it on
   * their own user-visible action — never silently from
   * `publishHistorical` — so the multi-minute cost is intentional.
   *
   * @param depth     how many recursive levels to fold; `1` =
   *                  per-chunk summaries only (flat); `2` =
   *                  summarize runs of `epochSize` per-chunk
   *                  summaries into epoch summaries; `3+` =
   *                  recursive narrowing until one summary remains
   *                  OR the limit is hit.
   * @param epochSize how many lower-level summaries fold into one
   *                  higher-level summary at each recursive step.
   *                  Default 8 → 64-summary input collapses to 8
   *                  epoch summaries collapses to one top summary
   *                  at depth=3.
   * Bug #144.
   */
  def compressHierarchical(sigil: Sigil,
                           callerModelId: Id[Model],
                           chain: List[ParticipantId],
                           frames: Vector[ContextFrame],
                           conversationId: Id[Conversation],
                           depth: Int = 1,
                           epochSize: Int = 8,
                           /** When supplied, the hierarchical pass emits a
                             * `control.step(...)` event before each leaf
                             * summarize, before each epoch fold, and at the
                             * end of every level so the activity bar reflects
                             * forward motion. Default `None` keeps the path
                             * silent for apps that haven't wired a workflow
                             * surface. Bug #145. */
                           control: Option[FrameworkWorkflowControl] = None): Task[Vector[ContextSummary]] = {
    if (frames.isEmpty) Task.pure(Vector.empty)
    else for {
      ctx <- loadContext(sigil, conversationId)
      // Route once for the whole hierarchical pass — every chunk
      // and every epoch fold goes through the same model.
      summarizationModel <- sigil.routedModelFor(
                              SummarizationWork,
                              chain,
                              fallback = callerModelId,
                              estimatedInputTokens = None,
                              reservedOutputTokens = reservedOutputTokens
                            ).handleError(_ => Task.pure(callerModelId))
      available = sigil.cache.find(summarizationModel).map(_.contextLength).getOrElse(0L) - reservedOutputTokens - promptOverheadTokens
      effectiveTokens = if (available <= 0L) Long.MaxValue else available
      // Stage 0 — leaf chunks. Bytes ceiling applied alongside the
      // token budget per bug #143.
      leafChunks = SummaryOnlyCompressor.chunkByTokensAndBytes(
                     frames, ctx, renderTranscript, tokenizer, effectiveTokens, maxChunkBytes
                   )
      total = leafChunks.size
      _ <- emit(control, s"Hierarchical compression: $total leaf chunks queued")
      // Counter for per-leaf progress. With parallelism > 1 the
      // chunks complete out of order; the counter still gives a
      // monotonic "k of N done" surface the UI can display.
      completed = new java.util.concurrent.atomic.AtomicInteger(0)
      leafTasks = leafChunks.map { chunk =>
                    val text = renderTranscript(chunk, ctx._1, ctx._2)
                    summarize(sigil, summarizationModel, chain, text, conversationId).flatMap { settled =>
                      val done = completed.incrementAndGet()
                      emit(control, s"Hierarchical compression: leaf $done / $total summarized").map(_ => settled)
                    }
                  }
      // Bounded parallelism: default 1 = serial (current
      // behaviour). Apps bump for hosted providers / multi-slot
      // local backends. Bug #145.
      summarised <- Task.parSequenceBounded(leafTasks, math.max(1, hierarchicalParallelism))
      leafSummaries = summarised.flatten.toVector
      _ <- emit(control, s"Leaf pass complete · ${leafSummaries.size} summaries persisted")
      // Recursive fold: if depth > 1 and we have more than
      // `epochSize` leaves, group + summarize into epoch
      // summaries and recurse. The fold runs through the same
      // progress + parallelism surface.
      top <- foldSummaries(sigil, summarizationModel, chain, leafSummaries, conversationId,
                            remainingDepth = depth - 1, epochSize = epochSize, control = control)
    } yield top
  }

  /** Fold a vector of summaries into epoch summaries — `epochSize`
    * inputs per fold — and recurse `remainingDepth` levels deeper.
    * Each epoch summary is persisted so the curator's
    * `summariesFor` lookup surfaces every level. */
  private def foldSummaries(sigil: Sigil,
                            modelId: Id[Model],
                            chain: List[ParticipantId],
                            summaries: Vector[ContextSummary],
                            conversationId: Id[Conversation],
                            remainingDepth: Int,
                            epochSize: Int,
                            control: Option[FrameworkWorkflowControl]): Task[Vector[ContextSummary]] = {
    if (remainingDepth <= 0 || summaries.size <= epochSize) Task.pure(summaries)
    else {
      val groups = summaries.grouped(epochSize).toVector
      val total  = groups.size
      val completed = new java.util.concurrent.atomic.AtomicInteger(0)
      val groupTasks = groups.toList.map { group =>
        val text = group.zipWithIndex.map { case (s, i) =>
          s"--- chunk ${i + 1} ---\n${s.text}"
        }.mkString("\n\n")
        summarize(sigil, modelId, chain, text, conversationId).flatMap { settled =>
          val done = completed.incrementAndGet()
          emit(control, s"Epoch fold (depth ${remainingDepth}): $done / $total summarized").map(_ => settled)
        }
      }
      for {
        _    <- emit(control, s"Epoch fold (depth ${remainingDepth}): $total groups queued")
        merged <- Task.parSequenceBounded(groupTasks, math.max(1, hierarchicalParallelism))
        epoch = merged.flatten.toVector
        _    <- emit(control, s"Epoch fold (depth ${remainingDepth}) complete · ${epoch.size} summaries")
        next <- foldSummaries(sigil, modelId, chain, epoch, conversationId, remainingDepth - 1, epochSize, control)
      } yield next
    }
  }

  /** Fire a progress step against the supplied control handle, if
    * any. Swallows control-side errors — a workflow-pulse hiccup
    * never blocks compression itself. Bug #145. */
  private def emit(control: Option[FrameworkWorkflowControl], label: String): Task[Unit] =
    control match {
      case Some(c) => c.step(label).handleError(_ => Task.unit)
      case None    => Task.unit
    }
}

object MemoryContextCompressor {
  val DefaultExtractionPrompt: String =
    """You are a fact extractor for an autonomous-agent framework.
      |
      |You'll be shown a conversation excerpt. Your job is to list durable, actionable facts that future
      |agents will need — identifiers, preferences, decisions, commitments, constraints.
      |
      |Output rules:
      |  - Self-contained: each fact must be readable without the transcript. Quote identifiers by name.
      |  - One idea per fact. Prefer ≤ 2 sentences.
      |  - Do NOT include filler, small-talk, acknowledgements, intermediate reasoning, or retried steps.
      |  - Do NOT include facts that belong in the narrative summary (goals, ongoing work) — those go to
      |    the summary pass that follows this one.
      |
      |Call the `extract_memories` tool with the list. Return an empty list if the excerpt carries no
      |durable facts.""".stripMargin

  /** Synthesise a short human-readable label from a compressor-
    * extracted fact. Takes the first sentence (or first ~60 chars,
    * whichever ends earlier) and trims trailing punctuation. */
  def synthesizeLabel(fact: String): String = {
    val firstClause = {
      val idx = fact.indexWhere(c => c == '.' || c == '!' || c == '?')
      if (idx > 0) fact.substring(0, idx) else fact
    }
    val truncated = if (firstClause.length > 60) firstClause.take(60).trim else firstClause.trim
    if (truncated.nonEmpty) truncated else fact.take(60).trim
  }
}
