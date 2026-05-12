package sigil.conversation.compression

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextSummary, Conversation}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, SummarizationWork}
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import sigil.tool.consult.{ConsultTool, SummarizationInput, SummarizationTool}

/**
 * Single-pass LLM compressor — renders the compressable frames via
 * [[TranscriptRenderer]], asks the consulted model to write a compact
 * summary through [[SummarizationTool]], and persists the result as a
 * [[ContextSummary]].
 *
 * Use [[sigil.conversation.compression.MemoryContextCompressor]] when
 * the app wants durable facts extracted into the memory store
 * alongside the summary.
 *
 * @param systemPrompt      role / instructions given to the consulted
 *                          model. The framework's default anchors on
 *                          what sigil conversations actually care
 *                          about (decisions, open questions,
 *                          participants); override for app-specific
 *                          emphasis.
 * @param renderTranscript  frames → plain-text transcript the
 *                          summarizer reads. Takes optional mode +
 *                          current topic when available (loaded from
 *                          the conversation). Default is
 *                          [[TranscriptRenderer.render]].
 */
case class SummaryOnlyCompressor(systemPrompt: String = SummaryOnlyCompressor.DefaultSystemPrompt,
                                 renderTranscript: SummaryOnlyCompressor.Renderer =
                                   SummaryOnlyCompressor.DefaultRenderer,
                                 tokenizer: Tokenizer = HeuristicTokenizer,
                                 reservedOutputTokens: Long = 1024L,
                                 promptOverheadTokens: Long = 512L,
                                 /** Per-chunk wire-protocol body ceiling. OpenAI caps
                                   * each `input[…].content[…].text` at 10 MB; llama.cpp
                                   * HTTP servers cap their request bodies similarly.
                                   * Default 8 MB stays comfortably under both. Apps
                                   * pointed at a provider with a different limit
                                   * override. Bug #143. */
                                 maxChunkBytes: Long = SummaryOnlyCompressor.DefaultMaxChunkBytes,
                                 /** Hard cap on the summarisation call's
                                   * generation. Same rationale as
                                   * [[MemoryContextCompressor.maxSummaryTokens]] —
                                   * without a cap the model can produce a
                                   * paraphrase the size of the input that
                                   * the server truncates mid-sentence and
                                   * the framework persists anyway. Bug
                                   * #148. */
                                 maxSummaryTokens: Int = 2048) extends ContextCompressor {

  override def compress(sigil: Sigil,
                        callerModelId: Id[Model],
                        chain: List[ParticipantId],
                        frames: Stream[ContextFrame],
                        conversationId: Id[Conversation]): Task[Option[ContextSummary]] =
    frames.toList.flatMap { framesList =>
      val materialized = framesList.toVector
      if (materialized.isEmpty) Task.pure(None)
      else for {
        ctx <- loadContext(sigil, conversationId)
        // Bug #41 — estimate transcript size so `routedModelFor`
        // can skip candidates whose contextLength can't fit.
        transcript = renderTranscript(materialized, ctx._1, ctx._2)
        estimatedInput = (tokenizer.count(transcript) + tokenizer.count(systemPrompt) + promptOverheadTokens).toLong
        // Bug #24 / #26 / #41 — route through a `SummarizationWork`
        // candidate sized for the input; fall back to the caller's
        // model when no strategy / candidate fits.
        summarizationModel <- resolveSummarizationModel(sigil, callerModelId, chain, Some(estimatedInput))
        // Bug #41 — if even the picked model can't fit (e.g. fallback
        // path with `routedModelFor` returning the caller's model
        // unchanged), chunk the frames into pieces that fit and merge.
        result <- compressOrChunk(sigil, summarizationModel, chain, materialized, ctx, conversationId)
      } yield result
    }

  /** Decide between single-shot and chunk-and-merge based on whether
    * the rendered transcript fits the picked model's context window.
    * `Model.contextLength` unknown → assume single-shot is fine. */
  private def compressOrChunk(sigil: Sigil,
                              modelId: Id[Model],
                              chain: List[ParticipantId],
                              frames: Vector[ContextFrame],
                              ctx: (Option[_root_.sigil.provider.Mode], Option[_root_.sigil.conversation.TopicEntry]),
                              conversationId: Id[Conversation]): Task[Option[ContextSummary]] = {
    val transcript = renderTranscript(frames, ctx._1, ctx._2)
    val transcriptTokens = tokenizer.count(transcript).toLong
    val transcriptBytes  = transcript.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
    val available = sigil.cache.find(modelId).map(_.contextLength).getOrElse(0L) - reservedOutputTokens - promptOverheadTokens
    // Single-shot path is viable only when BOTH the token window
    // accommodates the transcript AND the wire body fits the byte
    // ceiling. Bug #143 — token-only check was letting 18 MB
    // transcripts through to a 200K-context frontier model and
    // hitting the provider's per-text-input cap.
    val fitsTokens = available <= 0L || transcriptTokens <= available
    val fitsBytes  = transcriptBytes <= maxChunkBytes
    if (fitsTokens && fitsBytes)
      summarizeOnce(sigil, modelId, chain, transcript, conversationId)
    else
      chunkAndMerge(sigil, modelId, chain, frames, ctx, conversationId, available)
  }

  private def summarizeOnce(sigil: Sigil,
                            modelId: Id[Model],
                            chain: List[ParticipantId],
                            transcript: String,
                            conversationId: Id[Conversation]): Task[Option[ContextSummary]] = {
    val userPrompt = s"""Summarize the following conversation excerpt. Output via the `summarize_conversation` tool.
                        |
                        |${transcript}""".stripMargin
    ConsultTool.invoke[SummarizationInput](
      sigil = sigil,
      modelId = modelId,
      chain = chain,
      systemPrompt = systemPrompt,
      userPrompt = userPrompt,
      tool = SummarizationTool,
      generationSettings = GenerationSettings(maxOutputTokens = Some(maxSummaryTokens))
    ).flatMap {
      case Some(r) if r.summary.trim.nonEmpty =>
        val summary = ContextSummary(
          text = r.summary.trim,
          conversationId = conversationId,
          tokenEstimate = math.max(1, r.tokenEstimate)
        )
        sigil.persistSummary(summary).map(Some(_))
      case _ => Task.pure(None)
    }.handleError { e =>
      Task {
        scribe.warn(s"SummaryOnlyCompressor: compression call failed for conversation ${conversationId.value}: ${e.getMessage}")
        None
      }
    }
  }

  /** Bug #41 fallback — input exceeds the picked model's window. Split
    * `frames` into chunks each sized for `availableTokens`,
    * summarize each, then merge the per-chunk summaries with one
    * final call. Bounded memory throughout: each chunk's frames are
    * dropped after `summarizeOnce` returns. */
  private def chunkAndMerge(sigil: Sigil,
                            modelId: Id[Model],
                            chain: List[ParticipantId],
                            frames: Vector[ContextFrame],
                            ctx: (Option[_root_.sigil.provider.Mode], Option[_root_.sigil.conversation.TopicEntry]),
                            conversationId: Id[Conversation],
                            availableTokens: Long): Task[Option[ContextSummary]] = {
    val effectiveTokenBudget = if (availableTokens <= 0L) Long.MaxValue else availableTokens
    val chunks = SummaryOnlyCompressor.chunkByTokensAndBytes(
      frames, ctx, renderTranscript, tokenizer, effectiveTokenBudget, maxChunkBytes
    )
    val perChunkTask: Task[List[ContextSummary]] = Task.sequence(chunks.map { chunk =>
      val text = renderTranscript(chunk, ctx._1, ctx._2)
      summarizeOnce(sigil, modelId, chain, text, conversationId)
    }).map(_.flatten)
    perChunkTask.flatMap { perChunk =>
      if (perChunk.isEmpty) Task.pure(None)
      else if (perChunk.size == 1) Task.pure(perChunk.headOption)
      else {
        val mergePrompt =
          "Merge the following per-chunk summaries (in chronological order) into a single coherent narrative summary. " +
            "Output via the `summarize_conversation` tool.\n\n" +
            perChunk.zipWithIndex.map { case (s, i) => s"--- chunk ${i + 1} ---\n${s.text}" }.mkString("\n\n")
        summarizeOnce(sigil, modelId, chain, mergePrompt, conversationId)
      }
    }
  }

  /** Resolve a model for the summarization call. Bug #26 / #41 —
    * prefer a `SummarizationWork`-routed candidate sized for the
    * input; fall back to the caller's model when the strategy returns
    * no candidates. */
  private def resolveSummarizationModel(sigil: Sigil,
                                        callerModelId: Id[Model],
                                        chain: List[ParticipantId],
                                        estimatedInputTokens: Option[Long]): Task[Id[Model]] =
    sigil.routedModelFor(
      SummarizationWork,
      chain,
      fallback = callerModelId,
      estimatedInputTokens = estimatedInputTokens,
      reservedOutputTokens = reservedOutputTokens
    ).handleError(_ => Task.pure(callerModelId))

  /** Load the active conversation so the renderer can scope the
    * transcript with mode + topic. Missing conversation (shouldn't
    * happen in the live path) falls through with empty scope. */
  private def loadContext(sigil: Sigil, conversationId: Id[Conversation]) =
    sigil.withDB(_.conversations.transaction(_.get(conversationId))).map {
      case Some(conv) => (Some(conv.currentMode), conv.topics.lastOption)
      case None       => (None, None)
    }
}

object SummaryOnlyCompressor {
  /** Renderer signature — frames + optional mode + optional topic → transcript. */
  type Renderer = (Vector[ContextFrame],
                   Option[sigil.provider.Mode],
                   Option[sigil.conversation.TopicEntry]) => String

  val DefaultRenderer: Renderer = TranscriptRenderer.render

  /** Default per-chunk wire-protocol body ceiling. OpenAI's
    * Responses API caps each text input field at 10 MB; llama.cpp's
    * HTTP server typically caps lower. 8 MB stays comfortably under
    * both without inflating chunk count on normal-sized inputs.
    * Bug #143. */
  val DefaultMaxChunkBytes: Long = 8L * 1024L * 1024L

  /** Split a frame vector into chunks each rendering to ≤
    * `budgetTokens` per `tokenizer`. Greedy: walks frames in order,
    * accumulates into the current chunk until adding the next frame
    * would exceed the budget, then starts a new chunk. Single frames
    * larger than the budget land in their own chunk on their own
    * (the chunk-and-merge path can't sub-split a single frame; the
    * downstream `summarizeOnce` will let the provider handle it or
    * fail loudly). Bug #41. */
  def chunkByTokens(frames: Vector[ContextFrame],
                    ctx: (Option[_root_.sigil.provider.Mode], Option[_root_.sigil.conversation.TopicEntry]),
                    render: Renderer,
                    tokenizer: sigil.tokenize.Tokenizer,
                    budgetTokens: Long): List[Vector[ContextFrame]] =
    chunkByTokensAndBytes(frames, ctx, render, tokenizer, budgetTokens, maxBytes = Long.MaxValue)

  /** Split a frame vector into chunks satisfying BOTH a token budget
    * AND a byte ceiling — splits whenever adding the next frame would
    * exceed either constraint. The byte ceiling captures the wire-
    * protocol body cap that every provider enforces below the model's
    * claimed token context (OpenAI: 10 MB per input field, llama.cpp:
    * server-configured but typically smaller, …). Token-window math
    * is necessary but not sufficient. Single frames whose own size
    * exceeds either budget land alone in their chunk — caller decides
    * whether to refuse or let the provider handle it. Bug #143. */
  def chunkByTokensAndBytes(frames: Vector[ContextFrame],
                            ctx: (Option[_root_.sigil.provider.Mode], Option[_root_.sigil.conversation.TopicEntry]),
                            render: Renderer,
                            tokenizer: sigil.tokenize.Tokenizer,
                            budgetTokens: Long,
                            maxBytes: Long): List[Vector[ContextFrame]] = {
    val out = scala.collection.mutable.ListBuffer.empty[Vector[ContextFrame]]
    var current = Vector.empty[ContextFrame]
    var currentTokens = 0L
    var currentBytes  = 0L
    frames.foreach { frame =>
      val rendered    = render(Vector(frame), ctx._1, ctx._2)
      val frameTokens = tokenizer.count(rendered).toLong
      val frameBytes  = rendered.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
      val wouldExceed =
        current.nonEmpty &&
          ((currentTokens + frameTokens > budgetTokens) ||
            (currentBytes + frameBytes > maxBytes))
      if (wouldExceed) {
        out += current
        current = Vector(frame)
        currentTokens = frameTokens
        currentBytes  = frameBytes
      } else {
        current = current :+ frame
        currentTokens += frameTokens
        currentBytes  += frameBytes
      }
    }
    if (current.nonEmpty) out += current
    out.toList
  }

  val DefaultSystemPrompt: String =
    """You are a conversation summarizer for an autonomous-agent framework.
      |
      |The excerpt you'll receive is a slice of an ongoing conversation between a user, one or more
      |AI agents, and tool-call/result pairs. Your summary will REPLACE this slice in every future turn —
      |the agents won't see the original text again. Write so that an agent resuming the conversation
      |three days later can still act on it.
      |
      |Preserve:
      |  - facts, names, identifiers, numbers, and URLs that were asserted
      |  - decisions that were made and who made them
      |  - open questions and commitments ("we still need X", "you agreed to Y")
      |  - meaningful context for ongoing work (goals, constraints, deadlines)
      |
      |Drop:
      |  - small-talk, acknowledgements, and filler
      |  - intermediate reasoning or retries the agents would not re-read
      |  - duplicate content expressed more than once
      |
      |Style:
      |  - third-person narrative in past tense
      |  - refer to participants by name or role, not "I" / "you"
      |  - one tight paragraph unless the excerpt is long and fact-dense, in which case add a second
      |  - do NOT include a preamble or sign-off — just the summary body
      |
      |Call the `summarize_conversation` tool with your output. Do not respond in prose.""".stripMargin
}
