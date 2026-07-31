package sigil.tool.random

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolExample, ToolName, ToolProfile, ToolSpec}
import sigil.tool.model.{RandomDoubleInput, RandomDoubleOutput}

/**
 * Generate a uniformly random double in `[min, max)` — half-open
 * range, matching `scala.util.Random.between` semantics. Defaults to
 * `[0.0, 1.0)` when `min` / `max` are omitted.
 */
case object RandomDoubleTool extends Tool {
  type Input  = RandomDoubleInput
  type Output = RandomDoubleOutput
  val inputRW  = summon[RW[RandomDoubleInput]]
  val outputRW = summon[RW[RandomDoubleOutput]]

  override val name = ToolName("random_double")
  override val description =
    """Generate a uniformly random double in `[min, max)` — half-open (max exclusive).
      |
      |Defaults to the unit interval `[0.0, 1.0)`. Optional `seed` for reproducibility.
      |Returns `{value, min, max, seed}`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = Set("random", "rand", "double", "float", "decimal", "number", "rng"))
  )
  override val examples = List(
    ToolExample("unit-interval draw", RandomDoubleInput()),
    ToolExample("ranged seeded draw", RandomDoubleInput(min = -1.0, max = 1.0, seed = Some(7L)))
  )

  override def executeOutput(input: RandomDoubleInput, context: ToolContext): Task[RandomDoubleOutput] = Task {
    require(input.min < input.max, s"random_double: min (${input.min}) must be < max (${input.max})")
    val rng = input.seed.map(s => new scala.util.Random(s)).getOrElse(scala.util.Random)
    RandomDoubleOutput(
      value = rng.between(input.min, input.max),
      min   = input.min,
      max   = input.max,
      seed  = input.seed
    )
  }
}
