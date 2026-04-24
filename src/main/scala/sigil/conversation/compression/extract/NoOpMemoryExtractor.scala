package sigil.conversation.compression.extract

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextMemory, Conversation}
import sigil.db.Model
import sigil.participant.ParticipantId

/** Default [[MemoryExtractor]] — never extracts anything. Apps that
  * want per-turn extraction override `Sigil.memoryExtractor`. */
object NoOpMemoryExtractor extends MemoryExtractor {
  override def extract(sigil: Sigil,
                       conversationId: Id[Conversation],
                       modelId: Id[Model],
                       chain: List[ParticipantId],
                       userMessage: String,
                       agentResponse: String): Task[List[ContextMemory]] =
    Task.pure(Nil)
}
