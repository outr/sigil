package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextSummary, Conversation}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.consult.{ConsultTool, SummarizationInput, SummarizationTool}

/**
 * Single-pass LLM compressor — renders the compressable frames into a
 * plain transcript, asks the consulted model to write a compact
 * summary via [[SummarizationTool]], and persists the result as a
 * [[ContextSummary]]. Provider errors are swallowed (log + return
 * None); the curator treats `None` as "skip compression this turn"
 * and keeps the frames in-place.
 *
 * This is the simple default. Richer strategies — memory-extracting
 * compression, block extraction to Information records — live in
 * their own implementations (roadmap).
 */
case class SummaryOnlyCompressor(systemPrompt: String = SummaryOnlyCompressor.DefaultSystemPrompt) extends ContextCompressor {
  override def compress(sigil: Sigil,
                        modelId: Id[Model],
                        chain: List[ParticipantId],
                        frames: Vector[ContextFrame],
                        conversationId: Id[Conversation]): Task[Option[ContextSummary]] = {
    if (frames.isEmpty) Task.pure(None)
    else {
      val userPrompt = s"Summarize the following conversation excerpt:\n\n${renderTranscript(frames)}"
      ConsultTool.invoke[SummarizationInput](
        sigil = sigil,
        modelId = modelId,
        chain = chain,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        tool = SummarizationTool
      ).flatMap {
        case Some(result) if result.summary.trim.nonEmpty =>
          val summary = ContextSummary(
            text = result.summary.trim,
            conversationId = conversationId,
            tokenEstimate = math.max(1, result.tokenEstimate)
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
  }

  /** Best-effort plain-text rendering of frames for the summarizer to
    * read. Tool calls become bracketed annotations; the point is a
    * readable narrative, not a faithful wire recreation. */
  private def renderTranscript(frames: Vector[ContextFrame]): String = {
    val sb = new StringBuilder
    frames.foreach {
      case ContextFrame.Text(content, participantId, _) =>
        sb.append(s"[${participantId.value}]: $content\n")
      case ContextFrame.ToolCall(toolName, argsJson, _, participantId, _) =>
        sb.append(s"[${participantId.value} -> ${toolName.value}]: $argsJson\n")
      case ContextFrame.ToolResult(_, content, _) =>
        sb.append(s"[tool result]: $content\n")
      case ContextFrame.System(content, _) =>
        sb.append(s"[system]: $content\n")
    }
    sb.toString
  }
}

object SummaryOnlyCompressor {
  val DefaultSystemPrompt: String =
    """You are a conversation summarizer.
      |Your job is to condense a conversation excerpt into a compact, third-person narrative that preserves
      |durable facts, names, decisions, and open questions while dropping small-talk and intermediate reasoning.
      |
      |Call the `summarize_conversation` tool with your summary. Do not respond in prose.""".stripMargin
}
