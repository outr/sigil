package sigil.conversation.compression.extract

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, Conversation}
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

  /** Compression-time extraction over the about-to-be-shed frame
    * slice. Default reduces the slice to a transcript and delegates
    * to [[extract]] with the user-side text concatenated as
    * `userMessage` and the agent-side text as `agentResponse`.
    * Apps override for frame-aware extraction (e.g. type-aware
    * branching by `ContextFrame` subtype). */
  def extractFromFrames(sigil: Sigil,
                        conversationId: Id[Conversation],
                        modelId: Id[Model],
                        chain: List[ParticipantId],
                        frames: Vector[ContextFrame]): Task[List[ContextMemory]] = {
    val callerOpt = chain.lastOption
    val (userText, agentText) = frames.foldLeft((List.empty[String], List.empty[String])) {
      case ((users, agents), frame) =>
        val text = frame match {
          case t: ContextFrame.Text       => Some(t.content -> Option(t.participantId))
          case tr: ContextFrame.ToolResult => Some(tr.content -> None)
          case s: ContextFrame.System     => Some(s.content -> None)
          case _                          => None
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
    extract(
      sigil          = sigil,
      conversationId = conversationId,
      modelId        = modelId,
      chain          = chain,
      userMessage    = userText.mkString("\n"),
      agentResponse  = agentText.mkString("\n")
    )
  }
}
