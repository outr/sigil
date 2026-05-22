package sigil.tool.provider

import fabric.io.JsonFormatter
import fabric.rw.*
import fabric.{arr, obj, str}
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}

/** List provider strategies visible to the caller in the
  * conversation's space, including a marker for the currently-
  * assigned strategy. Pair with [[SwitchModelTool]] for a "show
  * options then pick one" UX.
  *
  * **Not auto-registered.** Apps add to `staticTools` to expose. */
case object ListProviderStrategiesTool extends Tool {
  type Input  = ListProviderStrategiesInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[ListProviderStrategiesInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("list_provider_strategies")
  val description =
    "List provider strategies saved under the current conversation's space, " +
      "including a marker for the currently-assigned one."
  override val keywords = Set("list", "provider", "strategy", "strategies", "models")

  override def executeResult(input: ListProviderStrategiesInput,
                             ctx: TurnContext): Task[ToolResult[TextToolOutput]] =
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
      ToolResult.Success(TextToolOutput(JsonFormatter.Compact(payload)))
    }
}
