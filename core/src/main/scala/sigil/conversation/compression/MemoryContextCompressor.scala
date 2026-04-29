package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, Conversation, MemorySource}
import sigil.SpaceId
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.consult.{ConsultTool, ExtractMemoriesInput, ExtractMemoriesTool, SummarizationInput, SummarizationTool}

/**
 * Two-pass LLM compressor. First pass asks the consulted model to
 * extract a list of durable facts (via [[ExtractMemoriesTool]]); the
 * framework persists each fact as a [[ContextMemory]] in the space
 * returned by [[sigil.Sigil.compressionMemorySpace]]. Second pass
 * summarizes the excerpt the normal way (via [[SummarizationTool]]),
 * so the summary is free to stay short — the facts it would otherwise
 * repeat are already on disk.
 *
 * This is the compression-time pathway. For per-turn extraction (fire
 * after every agent response, before compression thresholds trigger),
 * see
 * [[sigil.conversation.compression.extract.StandardMemoryExtractor]]
 * which uses richer keyed memories via
 * [[sigil.tool.consult.ExtractMemoriesWithKeysTool]] +
 * `Sigil.upsertMemoryByKey` for automatic versioning.
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
                                   extractFacts: Boolean = true) extends ContextCompressor {

  override def compress(sigil: Sigil,
                        modelId: Id[Model],
                        chain: List[ParticipantId],
                        frames: Vector[ContextFrame],
                        conversationId: Id[Conversation]): Task[Option[ContextSummary]] = {
    if (frames.isEmpty) Task.pure(None)
    else for {
      ctx <- loadContext(sigil, conversationId)
      transcript = renderTranscript(frames, ctx._1, ctx._2)
      spaceOpt <- if (extractFacts) sigil.compressionMemorySpace(conversationId) else Task.pure(None)
      _ <- spaceOpt match {
             case Some(space) => extractAndPersist(sigil, modelId, chain, transcript, conversationId, space)
             case None        => Task.unit
           }
      summary <- summarize(sigil, modelId, chain, transcript, conversationId)
    } yield summary
  }

  private def extractAndPersist(sigil: Sigil,
                                modelId: Id[Model],
                                chain: List[ParticipantId],
                                transcript: String,
                                conversationId: Id[Conversation],
                                space: SpaceId): Task[Unit] = {
    val userPrompt =
      s"""List durable facts from the following conversation excerpt. Output via the `extract_memories` tool.
         |
         |${transcript}""".stripMargin
    ConsultTool.invoke[ExtractMemoriesInput](
      sigil = sigil,
      modelId = modelId,
      chain = chain,
      systemPrompt = extractionSystemPrompt,
      userPrompt = userPrompt,
      tool = ExtractMemoriesTool
    ).flatMap {
      case Some(result) =>
        val kept = result.facts.map(_.trim).filter(_.length >= minFactChars)
        Task.sequence(kept.map { fact =>
          sigil.persistMemoryFor(ContextMemory(
            fact = fact,
            source = MemorySource.Compression,
            spaceId = space,
            conversationId = Some(conversationId)
          ), chain, conversationId)
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
      tool = SummarizationTool
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
}
