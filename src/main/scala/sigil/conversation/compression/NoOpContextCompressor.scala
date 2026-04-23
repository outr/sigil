package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextSummary, Conversation}
import sigil.db.Model
import sigil.participant.ParticipantId

/**
 * Default [[ContextCompressor]] — never compresses. Pairs with the
 * default [[sigil.Sigil.curate]] (identity) so apps that don't want
 * compression keep the framework behavior unchanged.
 */
object NoOpContextCompressor extends ContextCompressor {
  override def compress(sigil: Sigil,
                        modelId: Id[Model],
                        chain: List[ParticipantId],
                        frames: Vector[ContextFrame],
                        conversationId: Id[Conversation]): Task[Option[ContextSummary]] =
    Task.pure(None)
}
