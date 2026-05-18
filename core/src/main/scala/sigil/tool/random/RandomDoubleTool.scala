package sigil.tool.random

import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.tool.model.{RandomDoubleInput, RandomDoubleOutput}

/**
 * Generate a uniformly random double in `[min, max)` — half-open
 * range, matching `scala.util.Random.between` semantics. Defaults to
 * `[0.0, 1.0)` when `min` / `max` are omitted.
 */
case object RandomDoubleTool
  extends TypedOutputTool[RandomDoubleInput, RandomDoubleOutput](
    name = ToolName("random_double"),
    description =
      """Generate a uniformly random double in `[min, max)` — half-open (max exclusive).
      |
      |Defaults to the unit interval `[0.0, 1.0)`. Optional `seed` for reproducibility.
      |Returns `{value, min, max, seed}`.""".stripMargin,
    examples = List(
      ToolExample("unit-interval draw", RandomDoubleInput()),
      ToolExample("ranged seeded draw", RandomDoubleInput(min = -1.0, max = 1.0, seed = Some(7L)))
    ),
    keywords = Set("random", "rand", "double", "float", "decimal", "number", "rng")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: RandomDoubleInput, context: TurnContext): Task[RandomDoubleOutput] = Task {
    require(input.min < input.max, s"random_double: min (${input.min}) must be < max (${input.max})")
    val rng = input.seed.map(s => new scala.util.Random(s)).getOrElse(scala.util.Random)
    RandomDoubleOutput(
      value = rng.between(input.min, input.max),
      min = input.min,
      max = input.max,
      seed = input.seed
    )
  }
}
