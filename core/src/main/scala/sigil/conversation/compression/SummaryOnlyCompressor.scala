package sigil.conversation.compression

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextSummary, Conversation}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.SummarizationWork
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
                                   SummaryOnlyCompressor.DefaultRenderer) extends ContextCompressor {

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
        // Bug #24 / #26 — route the summarization through a
        // SummarizationWork-tier model rather than inheriting the
        // caller's model. Falls back to the caller's model when no
        // SummarizationWork candidate is available.
        summarizationModel <- resolveSummarizationModel(sigil, callerModelId, chain)
        transcript = renderTranscript(materialized, ctx._1, ctx._2)
        userPrompt = s"""Summarize the following conversation excerpt. Output via the `summarize_conversation` tool.
                        |
                        |${transcript}""".stripMargin
        result <- ConsultTool.invoke[SummarizationInput](
                    sigil = sigil,
                    modelId = summarizationModel,
                    chain = chain,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    tool = SummarizationTool
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
      } yield result
    }

  /** Resolve a model for the summarization call. Bug #26 — prefer a
    * `SummarizationWork`-routed candidate via the framework's
    * provider-strategy chain; fall back to the caller's model when
    * the strategy returns no candidates. */
  private def resolveSummarizationModel(sigil: Sigil,
                                        callerModelId: Id[Model],
                                        chain: List[ParticipantId]): Task[Id[Model]] =
    sigil.routedModelFor(SummarizationWork, chain, fallback = callerModelId)
      .handleError(_ => Task.pure(callerModelId))

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
