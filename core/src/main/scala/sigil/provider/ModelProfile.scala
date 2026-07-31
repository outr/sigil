package sigil.provider

import fabric.rw.*
import sigil.db.Model

/**
 * What a model is behaviorally capable of, as a fact the framework's
 * scaffolding can consult.
 *
 * `Model` carries wire metadata — context length, pricing, capability
 * flags. This carries behavior: how well the model follows multi-step
 * instructions, how dependably it emits tool calls, how much of its
 * window it actually uses well, and whether it wants oversight. The
 * checkpoint cadence, planner arming, discovery roster ceiling, and
 * prompt verbosity all read it instead of each carrying its own knob.
 *
 * @param instructionTier     multi-step instruction-following strength
 * @param toolCallReliability how dependably tool calls come out well-formed
 * @param contextComfort      tokens the model uses WELL — not its max window
 * @param needsOversight      arm the planner on every cadence tick
 * @param promptShape         section verbosity
 */
case class ModelProfile(instructionTier: InstructionTier,
                        toolCallReliability: Reliability,
                        contextComfort: Int,
                        needsOversight: Boolean,
                        promptShape: PromptShape) derives RW

object ModelProfile {

  /** The profile every unrecognized model gets: treat it as capable and
    * change nothing. Existing deployments keep their exact behavior
    * until an app declares otherwise. */
  def default(model: Model): ModelProfile = ModelProfile(
    instructionTier = InstructionTier.Frontier,
    toolCallReliability = Reliability.Solid,
    contextComfort = model.contextLength.toInt,
    needsOversight = false,
    promptShape = PromptShape.Full
  )
}
