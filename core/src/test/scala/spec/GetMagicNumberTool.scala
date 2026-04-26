package spec

import fabric.rw.*
import sigil.TurnContext
import sigil.event.{Event, Message, Role}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/** Empty input — `get_magic_number` takes no arguments. */
final case class GetMagicNumberInput() extends ToolInput derives RW

/**
 * Test-only tool used by [[MultiStepToolFlowSpec]] to demonstrate the
 * multi-step tool-flow gap. Returns the literal string "42" via a
 * `Message(participantId = context.caller)` — the same emission
 * pattern the existing core tools (`LookupInformationTool`,
 * `RespondTool`) use.
 *
 * The point: this is the natural shape an app builder would write
 * for a data-returning tool, following the precedent set by every
 * existing core tool. The flaw the spec demonstrates is that the
 * agent's outer self-loop terminates after one iteration when the
 * tool emits a `Message`-from-self — `TriggerFilter` excludes it as
 * a re-trigger — so the agent never gets the chance to read the
 * result and compose a `respond` call.
 */
case object GetMagicNumberTool extends TypedTool[GetMagicNumberInput](
  name = ToolName("get_magic_number"),
  description = "Returns the magic number. Call this first, then tell the user what number you got."
) {
  override protected def executeTyped(input: GetMagicNumberInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(List[Event](
      Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text("42")),
        state = EventState.Complete,
        role = Role.Tool
      )
    ))
}
