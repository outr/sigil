package sigil.conversation.compression.extract

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextMemory, Conversation, MemorySource, MemoryStatus, MemoryType}
import sigil.SpaceId
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.{Mode, OutputTokenCap}
import sigil.tool.consult.{ConsultTool, ExtractMemoriesInput, ExtractMemoriesTool}

/**
 * Per-turn memory extractor:
 *
 *   1. Runs [[filter.isHighSignal]] on the turn; short-circuits
 *      with `Nil` on low-signal turns (no LLM call).
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
                                   systemPrompt: String = StandardMemoryExtractor.DefaultSystemPrompt,
                                   /**
                                    * Hard cap on the `extract_memories` consult's
                                    * generation. Sized so a rich user paste
                                    * (multi-KB structured artefact, an
                                    * assistant response listing 20+ facts)
                                    * can flush the full tool_use input
                                    * before the model hits the ceiling. The
                                    * prior 1500-token default truncated the
                                    * structured emission mid-buffer on large
                                    * inputs and produced a tool_use with
                                    * empty input — zero memories recorded,
                                    * silently. 8192 covers the worst-case
                                    * shape while staying well under the
                                    * 64K output ceiling of every current
                                    * frontier model.
                                    */
                                   maxExtractionTokens: Int = StandardMemoryExtractor.DefaultMaxExtractionTokens)
  extends MemoryExtractor {

  override def signalFilter: Option[HighSignalFilter] = Some(filter)

  override def extract(sigil: Sigil,
                       conversationId: Id[Conversation],
                       modelId: Id[Model],
                       chain: List[ParticipantId],
                       userMessage: String,
                       agentResponse: String): Task[List[ContextMemory]] =
    extractTurn(sigil, conversationId, modelId, chain, ExtractionTurn(userMessage, agentResponse))

  override def extractTurn(sigil: Sigil,
                           conversationId: Id[Conversation],
                           modelId: Id[Model],
                           chain: List[ParticipantId],
                           turn: ExtractionTurn): Task[List[ContextMemory]] =
    if (!filter.isHighSignal(turn)) Task.pure(Nil)
    else spaceIdFor(conversationId).flatMap {
      case None => Task.pure(Nil)
      case Some(space) =>
        val userPrompt =
          s"""Extract durable memories from the following exchange. Output via the
             |`extract_memories` tool.
             |
             |USER: ${turn.userMessage}
             |
             |AGENT: ${turn.agentResponse}""".stripMargin
        sigil.auxModelFor(conversationId, ExtractMemoriesTool.consultWorkType, chain, modelId).flatMap { routedModelId =>
          // Reasoning-off + tool name come from `ExtractMemoriesTool`'s
          // canonical consultSettings; the cap is stamped per-extractor
          // from [[maxExtractionTokens]] so apps can tune it without
          // forking the tool. Temperature is stamped per routed model for
          // deterministic extraction.
          val extractorSettings = {
            val base = ConsultTool.settingsFor(ExtractMemoriesTool)
              .copy(outputTokenCap = OutputTokenCap.Below(maxExtractionTokens), maxOutputTokens = None)
            if (sigil.supportsParameter(routedModelId, "temperature")) base.copy(temperature = Some(0.0))
            else base
          }
          ConsultTool.invoke[ExtractMemoriesInput](
            sigil = sigil,
            modelId = routedModelId,
            chain = chain,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            tool = ExtractMemoriesTool,
            generationSettings = extractorSettings
          )
        }.flatMap {
          case None => Task.pure(Nil)
          case Some(result) =>
            val kept = result.memories.filter(_.content.nonEmpty)
            val knownModes: Set[String] = sigil.availableModes.map(_.name).toSet
            val memories = kept.map { m =>
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
              ContextMemory(
                fact = m.content,
                label = m.label,
                summary = m.content,
                source = MemorySource.Compression,
                spaceId = space,
                key = m.key,
                keywords = otherTags.toVector,
                memoryType = defaultType,
                status = defaultStatus,
                conversationId = Some(conversationId),
                modeAffinity = resolvedModes,
                sourceEventIds = turn.sourceEventIds
              )
            }
            sigil.persistMemoriesFor(memories, chain, conversationId)
        }.handleError { e =>
          Task(scribe.warn(s"StandardMemoryExtractor: extraction failed for conversation ${conversationId.value}: ${e.getMessage}"))
            .map(_ => Nil)
        }
    }
}

object StandardMemoryExtractor {

  /**
   * Default ceiling on the `extract_memories` consult's output. Sized
   * so a rich excerpt (KB-scale paste, enumerative agent reply) can
   * flush its full structured `tool_use` payload. The 1500-token
   * prior default truncated the wire-buffered tool input on large
   * inputs, producing an empty `tool_use` and zero recorded memories.
   */
  val DefaultMaxExtractionTokens: Int = 8192

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
