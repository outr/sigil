package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ConversationView, TurnInput}
import sigil.db.Model
import sigil.information.InformationSummary
import sigil.participant.ParticipantId

/**
 * Default [[ContextCurator]]. Runs a fixed pipeline:
 *
 *   1. [[optimizer]] — cheap, stateless frame cleanup.
 *   2. [[blockExtractor]] — pull long content blocks out to
 *      [[sigil.information.Information]] records (off by default).
 *   3. [[memoryRetriever]] — surface relevant stored memories into
 *      `TurnInput.memories` so they render in the system prompt
 *      (off by default).
 *   4. Build a tentative [[TurnInput]] from the trimmed frames +
 *      extracted catalog entries + retrieved memory ids.
 *   5. Budget check via [[budget]] against the target model's
 *      context length.
 *   6. If over budget: split frames at `max(N/2, keepMinimum)`,
 *      compress the older half via [[compressor]], and swap the new
 *      summary id into the TurnInput.
 *
 * The persistent [[ConversationView]] is never mutated — compression
 * only edits the ephemeral TurnInput that flows to the provider. Any
 * information dropped from the rolling context stays on disk via the
 * event log + (for extracted blocks) the Information catalog, and is
 * surfaced on demand by `search_conversation` / `lookup_information`.
 *
 * Every pipeline stage has a NoOp default — apps opt in component by
 * component:
 *   - optimizer = StandardContextOptimizer with all rules on
 *   - blockExtractor = NoOpBlockExtractor
 *   - memoryRetriever = NoOpMemoryRetriever
 *   - compressor = NoOpContextCompressor
 *   - budget = Percentage(0.8)
 *
 * Swap any or all as needed.
 */
case class StandardContextCurator(sigil: Sigil,
                                  optimizer: ContextOptimizer = StandardContextOptimizer(),
                                  blockExtractor: BlockExtractor = NoOpBlockExtractor,
                                  memoryRetriever: MemoryRetriever = NoOpMemoryRetriever,
                                  compressor: ContextCompressor = NoOpContextCompressor,
                                  budget: ContextBudget = Percentage(0.8),
                                  keepMinimum: Int = 4) extends ContextCurator {

  override def curate(view: ConversationView,
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] = {
    val optimizedFrames = optimizer.optimize(view.frames)

    for {
      blockResult <- blockExtractor.extract(sigil, optimizedFrames)
      postBlockView = view.copy(frames = blockResult.frames)
      memoryResult <- memoryRetriever.retrieve(sigil, postBlockView, chain)
      tentative = TurnInput(
        conversationView = postBlockView,
        criticalMemories = memoryResult.criticalMemories,
        memories = memoryResult.memories,
        information = blockResult.information
      )
      model <- modelFor(modelId)
      result <- budgetResolve(model, postBlockView, blockResult.frames, tentative, modelId, chain, memoryResult, blockResult.information)
    } yield result
  }

  private def budgetResolve(model: Model,
                            postBlockView: ConversationView,
                            frames: Vector[ContextFrame],
                            tentative: TurnInput,
                            modelId: Id[Model],
                            chain: List[ParticipantId],
                            memoryResult: MemoryRetrievalResult,
                            information: Vector[InformationSummary]): Task[TurnInput] = {
    val cap = budget.tokensFor(model)
    val estimate = TokenEstimator.estimateFrames(frames)
    if (estimate <= cap || frames.size <= keepMinimum) Task.pure(tentative)
    else {
      val keep = math.max(keepMinimum, frames.size / 2)
      val (older, newer) = frames.splitAt(frames.size - keep)
      compressor.compress(sigil, modelId, chain, older, postBlockView.conversationId).map {
        case Some(summary) =>
          TurnInput(
            conversationView = postBlockView.copy(frames = newer),
            criticalMemories = memoryResult.criticalMemories,
            memories = memoryResult.memories,
            summaries = Vector(summary._id),
            information = information
          )
        case None =>
          tentative
      }
    }
  }

  private def modelFor(modelId: Id[Model]): Task[Model] =
    sigil.withDB(_.model.transaction(_.get(modelId))).map(_.getOrElse(
      throw new NoSuchElementException(s"Model ${modelId.value} not found in cache — cannot run curator")
    ))
}
