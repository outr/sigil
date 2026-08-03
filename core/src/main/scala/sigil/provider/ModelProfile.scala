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

  /** Parameter count in billions as advertised in a model id or name —
    * `llama3.2:3b`, `qwen3.5-9b-q4_k_m`, `mixtral-8x7b`. The largest
    * match wins, so a quantization suffix or a mixture-of-experts
    * multiplier can't read as the model's size. */
  private val SizePattern = """(\d+(?:\.\d+)?)\s*[bB]\b""".r

  /** Families whose weakest member still follows multi-step instructions
    * and emits well-formed tool calls. */
  private val FrontierPattern =
    """(?i)(claude|gpt-4|gpt-5|\bo[13]\b|gemini-[^\s]*(pro|ultra)|grok)""".r

  /** Infer a profile from the model's id and name.
    *
    * Deliberately conservative: only the two signals that are reliable
    * from a bare identifier are used — an advertised parameter count
    * (small models get tighter oversight and a compact prompt) and
    * membership in a known frontier family. Anything unrecognized gets
    * [[default]], so a model the framework has never heard of behaves
    * exactly as it did before. Apps that know their fleet override
    * [[sigil.Sigil.modelProfileFor]] and skip the guessing entirely.
    */
  def heuristic(model: Model): ModelProfile = {
    val text = s"${model._id.value} ${model.name}"
    val declaredSize = SizePattern.findAllMatchIn(text).flatMap(m => m.group(1).toDoubleOption).maxOption
    declaredSize match {
      case Some(b) if b < 4 =>
        ModelProfile(InstructionTier.Minimal, Reliability.Wobbly, model.contextLength.toInt,
          needsOversight = true, promptShape = PromptShape.Compact)
      case Some(b) if b < 15 =>
        ModelProfile(InstructionTier.Small, Reliability.Wobbly, model.contextLength.toInt,
          needsOversight = false, promptShape = PromptShape.Compact)
      case _ =>
        if (FrontierPattern.findFirstIn(text).isDefined)
          ModelProfile(InstructionTier.Frontier, Reliability.Solid, model.contextLength.toInt,
            needsOversight = false, promptShape = PromptShape.Full)
        else default(model)
    }
  }
}
