package sigil.tool.model

import fabric.rw.*

/** Result of a `random_choice` pick. `index` is the 0-based position
  * the tool selected from the input `items` list — useful when the
  * caller needs to keep ordering context, or when the input items
  * carry side-information (positions, labels, etc.). */
case class RandomChoiceOutput(chosen: String,
                               index: Int,
                               itemCount: Int,
                               seed: Option[Long]) derives RW
