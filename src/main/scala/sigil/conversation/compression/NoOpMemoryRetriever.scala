package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextMemory, ConversationView}
import sigil.participant.ParticipantId

/**
 * Default [[MemoryRetriever]] — returns no memory ids. The curator's
 * output `TurnInput.memories` stays empty; nothing from the memory
 * store surfaces in the system prompt.
 */
object NoOpMemoryRetriever extends MemoryRetriever {
  override def retrieve(sigil: Sigil,
                        view: ConversationView,
                        chain: List[ParticipantId]): Task[Vector[Id[ContextMemory]]] =
    Task.pure(Vector.empty)
}
