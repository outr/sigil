package sigil.tool.provider

import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolName, TypedOutputTool}

/**
 * Read-only introspection tool that reports the conversation's
 * model-resolution state — pinned model, assigned strategy, last-used
 * model, and what dispatch would pick on the next turn. Pair with
 * [[ListModelsTool]] for "you're on X; here are alternatives".
 *
 * Typed output ([[CurrentModelOutput]]) lets other tools call
 * `CurrentModelTool.invoke(...)` directly — the alias resolver in
 * [[PinModelTool]] / [[SwitchModelTool]] uses this to map the friendly
 * `"current"` / `"this"` aliases to a real id without round-tripping
 * through rendered text.
 *
 * **Not auto-registered.** Apps add to `staticTools` to expose. */
case object CurrentModelTool extends TypedOutputTool[CurrentModelInput, CurrentModelOutput](
  name = ToolName("current_model"),
  description =
    """Report the model and strategy currently in effect for this conversation.
      |Returns:
      |  - `pinned` — set when `pin_model` is active; otherwise null.
      |  - `assignedStrategy` — saved strategy assigned to this conversation's
      |    space (from `switch_model` or `assignProviderStrategy`); null if none.
      |  - `lastUsed` — the model that produced the most recent agent Message in
      |    this conversation; null for fresh conversations.
      |  - `resolved` — the model dispatch would pick on the next turn after the
      |    pin / strategy / fallback chain.
      |
      |Use when you need to tell the user which model is in effect, or to resolve
      |"the current model" / "this model" before calling pin_model / switch_model.""".stripMargin,
  keywords = Set(
    "current", "active", "running", "model", "what", "which",
    "now", "introspect", "in", "use", "this"
  )
) {

  override protected def executeTyped(input: CurrentModelInput, ctx: TurnContext): Task[CurrentModelOutput] = {
    val conv = ctx.conversation
    val host = ctx.sigil

    val pinned = conv.pinnedModelId.map(toReference(host, _))

    val assignedStrategyTask = host.assignedProviderStrategy(conv.space).flatMap {
      case None => Task.pure(Option.empty[AssignedStrategySummary])
      case Some(strategyId) =>
        host.withDB(_.providerStrategies.transaction(_.get(strategyId))).map { recordOpt =>
          recordOpt.map { record =>
            AssignedStrategySummary(
              id               = record._id.value,
              label            = record.label,
              primaryCandidate = record.defaultCandidates.headOption.map(c => toReference(host, c.modelId))
            )
          }
        }
    }

    val lastUsedTask = host.lastUsedModel(conv.id).map(_.map(toReference(host, _)))

    val resolvedTask = host.currentModelFor(conv).map(_.map(toReference(host, _)))

    for {
      assignedStrategy <- assignedStrategyTask
      lastUsed         <- lastUsedTask
      resolved         <- resolvedTask
    } yield CurrentModelOutput(
      pinned           = pinned,
      assignedStrategy = assignedStrategy,
      lastUsed         = lastUsed,
      resolved         = resolved
    )
  }

  private def toReference(host: _root_.sigil.Sigil, modelId: lightdb.id.Id[_root_.sigil.db.Model]): ModelReference =
    ModelReference(
      id      = modelId.value,
      summary = host.cache.findTolerant(modelId).map(ModelSummary.from)
    )
}
