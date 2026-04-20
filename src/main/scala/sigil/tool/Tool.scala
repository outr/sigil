package sigil.tool

import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import sigil.TurnContext
import sigil.event.Event

trait Tool[Input <: ToolInput: RW] {
  protected def uniqueName: String
  protected def description: String
  protected def examples: List[ToolExample[Input]] = Nil

  /**
   * The RW codec for this tool's Input type. Used by provider accumulators to
   * deserialize tool-call arguments from JSON into the typed Input.
   */
  def inputRW: RW[Input] = summon[RW[Input]]

  lazy val schema: ToolSchema[Input] = ToolSchema(
    id = Id[ToolSchema[Input]](uniqueName),
    name = uniqueName,
    description = description,
    input = summon[RW[Input]].definition,
    examples = examples
  )

  def execute(input: Input, context: TurnContext): rapid.Stream[Event]
}
