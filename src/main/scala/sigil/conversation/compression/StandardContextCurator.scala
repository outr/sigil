package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ConversationView, TurnInput}
import sigil.db.Model
import sigil.participant.ParticipantId

/**
 * Default [[ContextCurator]]. Optimizes frames on every call; when the
 * estimated token count exceeds the [[ContextBudget]], compresses the
 * older half of frames via the configured [[ContextCompressor]] and
 * swaps the newly-minted summary id into the [[TurnInput]].
 *
 * The persistent [[ConversationView]] is never mutated — compression
 * only edits the ephemeral TurnInput that flows to the provider. Any
 * information "dropped" from the rolling context remains in the event
 * log + view and is surfaced by the `search_conversation` tool on
 * demand.
 *
 * `keepMinimum` floors the trimmed tail so compression doesn't strip
 * the conversation down to just a summary and a single message. The
 * split point is `max(frames.size / 2, keepMinimum)` from the end.
 */
case class StandardContextCurator(sigil: Sigil,
                                  compressor: ContextCompressor,
                                  budget: ContextBudget,
                                  optimizer: ContextOptimizer,
                                  keepMinimum: Int = 4) extends ContextCurator {

  override def curate(view: ConversationView,
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] = {
    val optimizedFrames = optimizer.optimize(view.frames)
    val optimizedView = view.copy(frames = optimizedFrames)
    val tentative = TurnInput(optimizedView)

    modelFor(modelId).flatMap { model =>
      val cap = budget.tokensFor(model)
      val estimate = TokenEstimator.estimateFrames(optimizedFrames)
      if (estimate <= cap || optimizedFrames.size <= keepMinimum) Task.pure(tentative)
      else {
        val keep = math.max(keepMinimum, optimizedFrames.size / 2)
        val (older, newer) = optimizedFrames.splitAt(optimizedFrames.size - keep)
        compressor.compress(sigil, modelId, chain, older, view.conversationId).map {
          case Some(summary) =>
            TurnInput(
              conversationView = optimizedView.copy(frames = newer),
              summaries = Vector(summary._id)
            )
          case None =>
            tentative
        }
      }
    }
  }

  private def modelFor(modelId: Id[Model]): Task[Model] =
    sigil.withDB(_.model.transaction(_.get(modelId))).map(_.getOrElse(
      throw new NoSuchElementException(s"Model ${modelId.value} not found in cache — cannot run curator")
    ))
}
