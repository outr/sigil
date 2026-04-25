package sigil.conversation.compression.extract

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextMemory, Conversation}
import sigil.db.Model
import sigil.participant.ParticipantId

/**
 * Extracts durable memories from a conversation turn. Invoked by the
 * [[sigil.orchestrator.Orchestrator]] after the agent's `Done` event
 * fires, on a background fiber — failures are logged but don't
 * affect the response stream.
 *
 * The default is [[NoOpMemoryExtractor]], meaning extraction is off
 * unless the app overrides `Sigil.memoryExtractor`. Apps wire a
 * concrete implementation (typically [[StandardMemoryExtractor]])
 * alongside a [[HighSignalFilter]] to skip extraction on low-value
 * utterances.
 *
 * Signature choices:
 *   - `userMessage` and `agentResponse` are the raw text of the turn;
 *     the extractor doesn't need the full transcript because
 *     memories are turn-scoped.
 *   - Returns the persisted memories so callers (tests, telemetry)
 *     can observe what landed.
 */
trait MemoryExtractor {
  def extract(sigil: Sigil,
              conversationId: Id[Conversation],
              modelId: Id[Model],
              chain: List[ParticipantId],
              userMessage: String,
              agentResponse: String): Task[List[ContextMemory]]
}
