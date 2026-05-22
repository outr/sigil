package sigil.tool.random

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{Tool, ToolExample, ToolName}
import sigil.tool.model.{RandomChoiceInput, RandomChoiceOutput}

/**
 * Pick one element uniformly at random from a list. Returns both the
 * chosen element and its index so callers can correlate with parallel
 * structures (positions, labels, side metadata).
 */
case object RandomChoiceTool extends Tool {
  type Input  = RandomChoiceInput
  type Output = RandomChoiceOutput
  val inputRW  = summon[RW[RandomChoiceInput]]
  val outputRW = summon[RW[RandomChoiceOutput]]

  val name = ToolName("random_choice")
  val description =
    """Pick one element uniformly at random from `items`.
      |
      |`items` must be non-empty. Optional `seed` for reproducibility.
      |Returns `{chosen, index, itemCount, seed}` — `index` lets the caller
      |correlate the pick with parallel arrays / lookup tables.""".stripMargin
  override val examples = List(
    ToolExample("pick a color", RandomChoiceInput(items = List("red", "green", "blue"))),
    ToolExample(
      "seeded pick — reproducible",
      RandomChoiceInput(items = List("alice", "bob", "carol"), seed = Some(99L))
    )
  )
  override val keywords = Set("random", "choose", "pick", "select", "sample", "choice")

  override def executeOutput(input: RandomChoiceInput, context: TurnContext): Task[RandomChoiceOutput] = Task {
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
