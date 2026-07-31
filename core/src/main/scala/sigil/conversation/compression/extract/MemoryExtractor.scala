package sigil.conversation.compression.extract

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ToolCallState}
import sigil.db.Model
import sigil.participant.ParticipantId

/**
 * Extracts durable memories from a conversation turn. Two callsites:
 *
 *   - Per-turn: [[sigil.orchestrator.Orchestrator]] invokes
 *     [[extract]] after the agent's `Done` event fires, on a
 *     background fiber.
 *   - Compression-time: [[sigil.conversation.compression.StandardContextCurator]]
 *     invokes [[extractFromFrames]] on the slice about to be
 *     summarised + dropped, so durable facts hidden inside older
 *     frames don't get collapsed away into a lossy summary.
 *
 * Failures are logged but don't affect the response or curator
 * pipeline. Default is [[NoOpMemoryExtractor]]; apps wire a
 * concrete implementation (typically [[StandardMemoryExtractor]])
 * alongside a [[HighSignalFilter]] to skip extraction on
 * low-value utterances.
 */
trait MemoryExtractor {
  def extract(sigil: Sigil,
              conversationId: Id[Conversation],
              modelId: Id[Model],
              chain: List[ParticipantId],
              userMessage: String,
              agentResponse: String): Task[List[ContextMemory]]

  /** Rich-turn entry point — the agent loop's post-turn extraction
    * builds an [[ExtractionTurn]] carrying the window's event ids and
    * settled tool mutations alongside the rendered text. The default
    * delegates to [[extract]] (text-only implementations keep
    * working); implementations that stamp provenance or gate on
    * structured turn evidence override this. */
  def extractTurn(sigil: Sigil,
                  conversationId: Id[Conversation],
                  modelId: Id[Model],
                  chain: List[ParticipantId],
                  turn: ExtractionTurn): Task[List[ContextMemory]] =
    extract(sigil, conversationId, modelId, chain, turn.userMessage, turn.agentResponse)

  /** Compression-time extraction over the about-to-be-shed frame
    * slice. Default reduces the slice to a transcript and delegates
    * to [[extractTurn]] with the user-side text concatenated as
    * `userMessage`, the agent-side text as `agentResponse`, and the
    * slice's source event ids as provenance. Apps override for
    * frame-aware extraction (e.g. type-aware branching by
    * `ContextFrame` subtype). */
  def extractFromFrames(sigil: Sigil,
                        conversationId: Id[Conversation],
                        modelId: Id[Model],
                        chain: List[ParticipantId],
                        frames: Vector[ContextFrame]): Task[List[ContextMemory]] = {
    val callerOpt = chain.lastOption
    val (userText, agentText) = frames.foldLeft((List.empty[String], List.empty[String])) {
      case ((users, agents), frame) =>
        val text = frame match {
          case t: ContextFrame.Text     => Some(t.content -> Option(t.participantId))
          case tc: ContextFrame.ToolCall =>
            // Sigil #261 — unified ToolCall(state): when Complete,
            // surface the tool result text for memory extraction the
            // same way the prior ContextFrame.ToolResult did.
            tc.state match {
              case ToolCallState.Complete(content, _) => Some(content -> None)
              case ToolCallState.Active               => None
            }
          case s: ContextFrame.System   => Some(s.content -> None)
          case _                        => None
        }
        text match {
          case Some((c, Some(pid))) if callerOpt.contains(pid) =>
            (users, agents :+ c)
          case Some((c, _)) =>
            (users :+ c, agents)
          case None =>
            (users, agents)
        }
    }
    extractTurn(
      sigil          = sigil,
      conversationId = conversationId,
      modelId        = modelId,
      chain          = chain,
      turn           = ExtractionTurn(
        userMessage    = userText.mkString("\n"),
        agentResponse  = agentText.mkString("\n"),
        sourceEventIds = frames.iterator.map(_.sourceEventId).distinct.toList
      )
    )
  }
}
