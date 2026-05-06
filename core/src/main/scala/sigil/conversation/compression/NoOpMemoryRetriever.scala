package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation}
import sigil.participant.ParticipantId

/**
 * Default [[MemoryRetriever]] — returns empty. The curator's output
 * leaves both `memories` and `criticalMemories` empty; nothing from
 * the memory store surfaces in the system prompt.
 */
object NoOpMemoryRetriever extends MemoryRetriever {
  override def retrieve(sigil: Sigil,
                        conversationId: Id[Conversation],
                        frames: Vector[ContextFrame],
                        chain: List[ParticipantId]): Task[MemoryRetrievalResult] =
    Task.pure(MemoryRetrievalResult.empty)
}
