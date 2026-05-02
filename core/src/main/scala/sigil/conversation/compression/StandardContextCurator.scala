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
    // Build the elide-set from each shipped Tool's `resultTtl`. The
    // standard policy is: any tool declaring `Some(0)` is ephemeral —
    // its ToolResults frame is redundant after the turn settles
    // (because the meaningful effect lives on a projection / System
    // frame / system prompt section). Tools with positive TTLs are
    // treated like `None` here (kept) — turn-count-aware elision is
    // future work and apps can extend this curator for it.
    val elide: Set[String] = sigil.staticTools.iterator
      .collect { case t if t.resultTtl.contains(0) => t.name.value }
      .toSet
    // Bug #73 — pass `chain.head` (the trigger originator, typically
    // the user) as the current-turn source so the optimizer preserves
    // within-turn iterations of ephemeral tools. Without this, mid-
    // turn agent loops on `find_capability` had their prior calls
    // elided across iterations, making each one look like a fresh
    // start to the model and producing identical-call retry loops
    // until `maxAgentIterations` fired.
    val optimizedFrames = optimizer.optimize(view.frames, elide, chain.headOption)

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
      modelOpt <- modelFor(modelId)
      result <- modelOpt match {
        case Some(model) =>
          budgetResolve(model, postBlockView, blockResult.frames, tentative, modelId, chain, memoryResult, blockResult.information)
        case None =>
          // No catalog record for this modelId. Either the provider
          // forgot to seed [[sigil.cache.ModelRegistry]] (a custom
          // provider's bug — framework-shipped providers seed at
          // construction) or the registry was wiped. Skip budget
          // compression and surface the optimized frames as-is.
          // Better to miss compression than to crash the agent loop
          // on the first turn.
          Task.pure(tentative)
      }
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

  /** Look up the target model in [[sigil.cache.ModelRegistry]].
    * Returns `None` when no record exists — the curator's caller
    * then short-circuits to the unbudgeted [[TurnInput]] rather than
    * crashing the agent loop. Apps that want a stricter posture
    * (fail-loud when a provider forgot to seed) extend this curator
    * and override [[curate]] directly. */
  private def modelFor(modelId: Id[Model]): Task[Option[Model]] =
    Task.pure(sigil.cache.find(modelId))
}
