package sigil.pipeline

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.event.{Event, Message}
import sigil.signal.{Delta, EventState, Signal}
import sigil.tool.model.{ResponseContent, ResponseContentOps}
import sigil.tool.model.ResponseContentOps.dereferenceAll
import sigil.vector.{NoOpVectorIndex, VectorPoint, VectorPointId}

/**
 * Default [[SettledEffect]] that embeds and upserts settled Messages
 * into the configured vector index so semantic search can retrieve
 * them.
 *
 * Runs synchronously within publish when vector search is wired — the
 * index must reflect the message before any subsequent read
 * (`searchConversationEvents`, `searchMemories`) is issued. When the
 * effect isn't wired (`embeddingProvider` is NoOp or `vectorIndex`
 * is NoOp), it short-circuits with zero cost.
 */
object MessageIndexingEffect extends SettledEffect {
  override def apply(signal: Signal, self: Sigil): Task[Unit] = {
    if (!vectorWired(self)) Task.unit
    else signal match {
      case m: Message if m.state == EventState.Complete => indexMessage(m, self)
      case d: Delta =>
        self.withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
          case Some(m: Message) if m.state == EventState.Complete => indexMessage(m, self)
          case _ => Task.unit
        }
      case _ => Task.unit
    }
  }

  private def vectorWired(self: Sigil): Boolean =
    self.embeddingProvider.dimensions > 0 && (self.vectorIndex ne NoOpVectorIndex)

  private def indexMessage(m: Message, self: Sigil): Task[Unit] =
    // Dereference any StoredFileReference blocks so the indexer sees
    // the original text, not the placeholder. Use the message's
    // author as the chain — apps' `accessibleSpaces` should grant the
    // author access to their own externalized content.
    dereferenceAll(self, List(m.participantId), m.content).flatMap { resolved =>
      val text = resolved.collect {
        case ResponseContent.Text(t)        => t
        case ResponseContent.Code(c, _)     => c
        case ResponseContent.Diff(d, _)     => d
        case ResponseContent.Markdown(t)    => t
        case ResponseContent.Heading(t)     => t
      }.mkString("\n").trim

      if (text.isEmpty) Task.unit
      else self.embeddingProvider.embed(text).flatMap { vec =>
        self.vectorIndex.upsert(VectorPoint(
          id = VectorPointId(m._id.value),
          vector = vec,
          payload = Map(
            "kind" -> "message",
            "conversationId" -> m.conversationId.value,
            "topicId" -> m.topicId.value,
            "eventId" -> m._id.value,
            "participantId" -> m.participantId.value
          )
        ))
      }.handleError { e =>
        Task(scribe.warn(s"Vector index failed for message ${m._id.value}: ${e.getMessage}"))
      }
    }
}
