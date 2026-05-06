package sigil.tool.random

import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.tool.model.{RandomIntInput, RandomIntOutput}

/**
 * Generate a uniformly random integer in `[min, max]` (inclusive).
 *
 * LLMs hallucinate "random" values badly — asking for "a number 1..10"
 * skews toward 7, asking for "a color" skews to blue, and so on. This
 * tool is the entropy escape hatch agents reach for when they need a
 * genuine draw (dice rolls, lottery / gacha sims, sample selection,
 * jitter, perturbation runs).
 *
 * Optional `seed` makes the draw reproducible — same seed, same value,
 * across machines. Useful for replayable simulations, deterministic
 * tests, or seeding sub-runs.
 */
case object RandomIntTool extends TypedOutputTool[RandomIntInput, RandomIntOutput](
  name = ToolName("random_int"),
  description =
    """Generate a uniformly random integer in `[min, max]` (inclusive on both ends).
      |
      |Optional `seed` makes the draw reproducible — same seed yields the same value
      |across calls and machines. Omit `seed` for genuine entropy.
      |
      |Errors if `min > max`. Returns `{value, min, max, seed}`.""".stripMargin,
  examples = List(
    ToolExample("d20 dice roll", RandomIntInput(min = 1, max = 20)),
    ToolExample("seeded coin flip (reproducible)", RandomIntInput(min = 0, max = 1, seed = Some(42L)))
  ),
  keywords = Set("random", "rand", "int", "integer", "number", "rng", "dice", "roll")
) {
  override protected def executeTyped(input: RandomIntInput, context: TurnContext): Task[RandomIntOutput] = Task {
    require(input.min <= input.max, s"random_int: min (${input.min}) must be <= max (${input.max})")
    val rng   = input.seed.map(s => new scala.util.Random(s)).getOrElse(scala.util.Random)
    val span  = BigInt(input.max) - BigInt(input.min) + 1
    // Range is open-on-the-right in Random.between; +1 makes max inclusive.
    val value = rng.between(input.min, input.min + span.toLong)
    RandomIntOutput(value = value, min = input.min, max = input.max, seed = input.seed)
  }
}
