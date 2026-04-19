package sigil.tool

import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.participant.ParticipantId

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

  def execute(input: Input, caller: ParticipantId, conversation: Conversation): rapid.Stream[Event]
}
