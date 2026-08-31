package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `random_choice` — pick a uniformly random element from
 * `items`. `items` must be non-empty; the tool errors if it isn't.
 */
case class RandomChoiceInput(items: List[String],
                             seed: Option[Long] = None)
  extends ToolInput derives RW
