package sigil.conversation.compression.extract

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextMemory, Conversation, MemorySource, MemorySpaceId, MemoryStatus, MemoryType}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.consult.{ConsultTool, ExtractMemoriesWithKeysInput, ExtractMemoriesWithKeysTool}

/**
 * Per-turn memory extractor:
 *
 *   1. Runs [[filter.isHighSignal]] on the user message; short-
 *      circuits with `Nil` on low-signal turns (no LLM call).
 *   2. Consults the configured model with
 *      [[ExtractMemoriesWithKeysTool]] wrapping the user message +
 *      agent response.
 *   3. For each extracted memory, resolves the target
 *      [[MemorySpaceId]] via `spaceIdFor`, then calls
 *      [[Sigil.upsertMemoryByKey]] so versioning happens
 *      automatically.
 *   4. Persists with `status = defaultStatus` (defaulting to
 *      `Pending`) so apps with an approval UX can gate auto-
 *      extracted facts before surfacing them.
 *
 * Apps pair this with a specific `modelId` (usually a cheap
 * extraction-tier model) and `chain` so the consult call has correct
 * participant attribution.
 */
case class StandardMemoryExtractor(filter: HighSignalFilter = DefaultHighSignalFilter,
                                   spaceIdFor: Id[Conversation] => Task[Option[MemorySpaceId]],
                                   defaultStatus: MemoryStatus = MemoryStatus.Pending,
                                   defaultType: MemoryType = MemoryType.Fact,
                                   systemPrompt: String = StandardMemoryExtractor.DefaultSystemPrompt)
  extends MemoryExtractor {

  override def extract(sigil: Sigil,
                       conversationId: Id[Conversation],
                       modelId: Id[Model],
                       chain: List[ParticipantId],
                       userMessage: String,
                       agentResponse: String): Task[List[ContextMemory]] = {
    if (!filter.isHighSignal(userMessage)) Task.pure(Nil)
    else spaceIdFor(conversationId).flatMap {
      case None        => Task.pure(Nil)
      case Some(space) =>
        val userPrompt =
          s"""Extract durable memories from the following exchange. Output via the
             |`extract_memories_with_keys` tool.
             |
             |USER: $userMessage
             |
             |AGENT: $agentResponse""".stripMargin
        ConsultTool.invoke[ExtractMemoriesWithKeysInput](
          sigil = sigil,
          modelId = modelId,
          chain = chain,
          systemPrompt = systemPrompt,
          userPrompt = userPrompt,
          tool = ExtractMemoriesWithKeysTool
        ).flatMap {
          case None => Task.pure(Nil)
          case Some(result) =>
            val kept = result.memories.filter(m => m.key.nonEmpty && m.content.nonEmpty)
            Task.sequence(kept.map { m =>
              sigil.upsertMemoryByKey(ContextMemory(
                fact = m.content,
                source = MemorySource.Compression,
                spaceId = space,
                key = m.key,
                label = m.label,
                summary = m.content,
                tags = m.tags.toVector,
                memoryType = defaultType,
                status = defaultStatus,
                conversationId = Some(conversationId)
              )).map(_.memory)
            })
        }.handleError { e =>
          Task(scribe.warn(s"StandardMemoryExtractor: extraction failed for conversation ${conversationId.value}: ${e.getMessage}"))
            .map(_ => Nil)
        }
    }
  }
}

object StandardMemoryExtractor {
  /**
   * Default system prompt for per-turn extraction. Tuned for Sigil's
   * surface (keys, tags, content).
   */
  val DefaultSystemPrompt: String =
    """You extract durable memories from a short exchange between a user and an agent.
      |
      |For each memory, emit a stable `key` (e.g. "user.preferred_language", "project.deploy_target"),
      |a short `label`, the full `content`, and optional `tags`. The same fact across conversations
      |should use the same key so it can be versioned rather than duplicated.
      |
      |Only emit content that is:
      |  - self-contained (a reader seeing it alone must still be able to act on it)
      |  - durable (will still matter in a future conversation)
      |  - specific (identifiers, numbers, URLs, preferences, decisions, commitments)
      |
      |Do NOT emit small-talk, intermediate reasoning, questions without answers, or content
      |that would be better captured by a summary.""".stripMargin
}
