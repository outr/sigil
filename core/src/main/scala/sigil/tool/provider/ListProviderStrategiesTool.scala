package sigil.tool.provider

import fabric.io.JsonFormatter
import fabric.{arr, num, obj, str}
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolName, TypedTool}

/** List provider strategies visible to the caller in the
  * conversation's space, including a marker for the currently-
  * assigned strategy. Pair with [[SwitchModelTool]] for a "show
  * options then pick one" UX.
  *
  * **Not auto-registered.** Apps add to `staticTools` to expose. */
case object ListProviderStrategiesTool extends TypedTool[ListProviderStrategiesInput](
  name = ToolName("list_provider_strategies"),
  description =
    "List provider strategies saved under the current conversation's space, " +
      "including a marker for the currently-assigned one.",
  keywords = Set("list", "provider", "strategy", "strategies", "models")
) {

  override protected def executeTyped(input: ListProviderStrategiesInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      for {
        records  <- ctx.sigil.listProviderStrategies(ctx.conversation.space, ctx.chain)
        assigned <- ctx.sigil.assignedProviderStrategy(ctx.conversation.space)
      } yield {
        val payload = obj(
          "space"    -> str(ctx.conversation.space.value),
          "assigned" -> assigned.map(id => str(id.value)).getOrElse(fabric.Null),
          "strategies" -> arr(records.map(r => obj(
            "id"    -> str(r._id.value),
            "label" -> str(r.label),
            "defaults" -> arr(r.defaultCandidates.map(c => str(c.modelId.value))*),
            "routes" -> obj(r.routeCandidates.map { case (workType, list) =>
              workType -> arr(list.map(c => str(c.modelId.value))*)
            }.toList*),
            "isAssigned" -> fabric.bool(assigned.contains(r._id))
          ))*)
        )
        Stream.emit[Event](Message(
          participantId  = ctx.caller,
          conversationId = ctx.conversation.id,
          topicId        = ctx.conversation.currentTopicId,
          content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
          state          = EventState.Complete,
          role           = MessageRole.Tool,
          visibility     = MessageVisibility.Agents
        ))
      }
    )
}
