package sigil.conversation.compression.extract

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextMemory, Conversation, MemorySource, MemoryStatus, MemoryType}
import sigil.SpaceId
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, Mode, ReasoningMode}
import sigil.tool.consult.{ConsultTool, ExtractMemoriesInput, ExtractMemoriesTool}

/**
 * Per-turn memory extractor:
 *
 *   1. Runs [[filter.isHighSignal]] on the user message; short-
 *      circuits with `Nil` on low-signal turns (no LLM call).
 *   2. Consults the configured model with
 *      [[ExtractMemoriesTool]] wrapping the user message +
 *      agent response.
 *   3. For each extracted memory, resolves the target
 *      [[SpaceId]] via `spaceIdFor`, then calls
 *      [[Sigil.upsertMemoryByKey]] so versioning happens
 *      automatically.
 *   4. Persists with `status = defaultStatus`. Default
 *      [[MemoryStatus.Approved]] — the framework's primary path
 *      surfaces extracted memories on the next turn without
 *      gating. Apps with a human-in-the-loop approval UX override
 *      to [[MemoryStatus.Pending]] and surface a review screen.
 *
 * Apps pair this with a specific `modelId` (usually a cheap
 * extraction-tier model) and `chain` so the consult call has correct
 * participant attribution.
 */
case class StandardMemoryExtractor(filter: HighSignalFilter = DefaultHighSignalFilter,
                                   spaceIdFor: Id[Conversation] => Task[Option[SpaceId]],
                                   defaultStatus: MemoryStatus = MemoryStatus.Approved,
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
             |`extract_memories` tool.
             |
             |USER: $userMessage
             |
             |AGENT: $agentResponse""".stripMargin
        // `maxOutputTokens = 1500` — extractor returns a list of
        // structured memories (typically 1-5 entries). On reasoning
        // models (Qwen3.6, DeepSeek-R1, o-series) the response burns
        // tokens on internal thinking before emitting the structured
        // tool call; the provider's default (often 512-1024) cut the
        // call off before the keys / tags filled in, producing
        // skeletal records with `key = None` across the board. 1500
        // covers the worst-case thinking budget while staying well
        // under any model's context window.
        val extractorSettings = {
          val base = GenerationSettings(
            maxOutputTokens = Some(1500),
            reasoningMode = ReasoningMode.Off
          )
          if (sigil.supportsParameter(modelId, "temperature")) base.copy(temperature = Some(0.0))
          else base
        }
        ConsultTool.invoke[ExtractMemoriesInput](
          sigil = sigil,
          modelId = modelId,
          chain = chain,
          systemPrompt = systemPrompt,
          userPrompt = userPrompt,
          tool = ExtractMemoriesTool,
          generationSettings = extractorSettings
        ).flatMap {
          case None => Task.pure(Nil)
          case Some(result) =>
            val kept = result.memories.filter(_.content.nonEmpty)
            // Sigil bug #195 — the extractor's tags carry an
            // optional `mode:NAME` side-channel that scopes the
            // memory to specific operating modes. Names that don't
            // match a registered Mode are dropped (with a WARN);
            // matched names become `Id[Mode]` entries in
            // `modeAffinity` and the prefix is stripped out of the
            // keywords. Going through the tags channel (rather than
            // adding a new field to ExtractedMemory) preserves the
            // smaller-model extractor's `key` emission — see
            // bugs/195-extractor-auto-mode-affinity.md for the
            // earlier discussion.
            val knownModes: Set[String] = sigil.availableModes.map(_.name).toSet
            Task.sequence(kept.map { m =>
              val (modeTagsRaw, otherTags) = m.tags.partition(_.startsWith("mode:"))
              val resolvedModes: Set[Id[Mode]] =
                modeTagsRaw.iterator.map(_.stripPrefix("mode:").trim).filter(_.nonEmpty).flatMap { name =>
                  if (knownModes.contains(name)) Some(Id[Mode](name))
                  else {
                    scribe.warn(s"extract_memories: dropping unknown mode tag 'mode:$name' " +
                      s"— not in availableModes [${knownModes.mkString(", ")}]")
                    None
                  }
                }.toSet
              val mem = ContextMemory(
                fact           = m.content,
                label          = m.label,
                summary        = m.content,
                source         = MemorySource.Compression,
                spaceId        = space,
                key            = m.key,
                keywords       = otherTags.toVector,
                memoryType     = defaultType,
                status         = defaultStatus,
                conversationId = Some(conversationId),
                modeAffinity   = resolvedModes
              )
              if (m.key.isDefined)
                sigil.upsertMemoryByKeyFor(mem, chain, conversationId).map(_.memory)
              else
                sigil.persistMemoryFor(mem, chain, conversationId)
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
