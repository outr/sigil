package sigil.tool.random

import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.tool.model.{RandomChoiceInput, RandomChoiceOutput}

/**
 * Pick one element uniformly at random from a list. Returns both the
 * chosen element and its index so callers can correlate with parallel
 * structures (positions, labels, side metadata).
 */
case object RandomChoiceTool extends TypedOutputTool[RandomChoiceInput, RandomChoiceOutput](
  name = ToolName("random_choice"),
  description =
    """Pick one element uniformly at random from `items`.
      |
      |`items` must be non-empty. Optional `seed` for reproducibility.
      |Returns `{chosen, index, itemCount, seed}` — `index` lets the caller
      |correlate the pick with parallel arrays / lookup tables.""".stripMargin,
  examples = List(
    ToolExample("pick a color", RandomChoiceInput(items = List("red", "green", "blue"))),
    ToolExample(
      "seeded pick — reproducible",
      RandomChoiceInput(items = List("alice", "bob", "carol"), seed = Some(99L))
    )
  ),
  keywords = Set("random", "choose", "pick", "select", "sample", "choice")
) {
  override protected def executeTyped(input: RandomChoiceInput, context: TurnContext): Task[RandomChoiceOutput] = Task {
    require(input.items.nonEmpty, "random_choice: `items` must be non-empty")
    val rng   = input.seed.map(s => new scala.util.Random(s)).getOrElse(scala.util.Random)
    val index = rng.nextInt(input.items.size)
    RandomChoiceOutput(
      chosen    = input.items(index),
      index     = index,
      itemCount = input.items.size,
      seed      = input.seed
    )
  }
}
