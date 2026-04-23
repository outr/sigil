package sigil.conversation.compression

import rapid.Task
import sigil.Sigil
import sigil.conversation.ConversationView
import sigil.participant.ParticipantId

/**
 * Default [[MemoryRetriever]] — returns empty. The curator's output
 * leaves both `memories` and `criticalMemories` empty; nothing from
 * the memory store surfaces in the system prompt.
 */
object NoOpMemoryRetriever extends MemoryRetriever {
  override def retrieve(sigil: Sigil,
                        view: ConversationView,
                        chain: List[ParticipantId]): Task[MemoryRetrievalResult] =
    Task.pure(MemoryRetrievalResult.empty)
}
