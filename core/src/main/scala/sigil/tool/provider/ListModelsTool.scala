package sigil.tool.provider

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolName, ToolProfile, ToolSpec}

/**
 * Read-only catalog listing — surfaces every model registered with
 * the running Sigil instance (cloud-provider catalog from OpenRouter,
 * locally-loaded models from [[sigil.provider.llamacpp.LlamaCppProvider]],
 * and any apps' merged additions). Filter by provider or substring
 * match against id / name / description.
 *
 * Pair with [[CurrentModelTool]] for "you're on X; here are
 * alternatives Y, Z, …".
 *
 * **Not auto-registered.** Apps add to `staticTools` to expose.
 */
case object ListModelsTool extends Tool {
  type Input = ListModelsInput
  type Output = ListModelsOutput
  val io: ToolIO[ListModelsInput, ListModelsOutput] = ToolIO.derived[ListModelsInput, ListModelsOutput]

  override val name = ToolName("list_models")
  override val description =
    """List models registered with this Sigil instance. Optionally filter by
      |provider (e.g., "openai", "anthropic", "local") or `query` for a
      |substring match against id / name / description.
      |
      |Returns each model's id, provider, context window, description, and
      |pricing. Includes both cloud-provider models (from the OpenRouter
      |catalog) and locally-loaded models.
      |
      |Use when the user asks "what models can I pin to?" or to disambiguate
      |a friendly name like "local" or "gpt" against the actual registry
      |before calling pin_model / switch_model.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set(
      "list",
      "models",
      "available",
      "provider",
      "options",
      "switch",
      "pin",
      "alternatives",
      "what",
      "which",
      "catalog"
    ))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: ListModelsInput, ctx: ToolContext): Task[ListModelsOutput] = Task {
    val all = ctx.sigil.cache.find(provider = input.provider, model = None)
    val q = input.query.map(_.toLowerCase.trim).filter(_.nonEmpty)
    val filtered = q match {
      case None => all
      case Some(needle) => all.filter { m =>
          m._id.value.toLowerCase.contains(needle) ||
          m.name.toLowerCase.contains(needle) ||
          m.description.toLowerCase.contains(needle)
        }
    }
    val sorted = filtered.sortBy(_._id.value)
    val total = sorted.size
    val window = input.limit.fold(sorted)(n => sorted.take(math.max(0, n)))
    ListModelsOutput(
      total = total,
      returned = window.size,
      models = window.map(ModelSummary.from)
    )
  }
}
