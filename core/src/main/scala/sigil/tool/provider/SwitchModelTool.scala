package sigil.tool.provider

import fabric.{arr, num, obj, str}
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.{SpaceId, TurnContext}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.provider.{ModelCandidate, ProviderStrategyRecord}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Switch the conversation's active provider strategy. Disambiguates
 * `query` against:
 *
 *   1. `"auto"` / `"default"` → unassign the current strategy
 *      (dispatch falls back to the agent's pinned `modelId`).
 *   2. An existing [[ProviderStrategyRecord]] by id or by label.
 *   3. A known model id → create an ad-hoc single-model strategy
 *      under the conversation's space and assign it.
 *
 * **Not auto-registered.** Apps that want this tool exposed to the
 * agent add it to `staticTools` explicitly:
 *
 * {{{
 *   override def staticTools: List[Tool] =
 *     super.staticTools :+ SwitchModelTool
 * }}}
 *
 * The tool's `space` is `GlobalSpace` so any agent can call it; the
 * actual switch operates on the conversation's `space`. Apps wanting
 * to gate switching by user role override their own `accessibleSpaces`
 * resolution to control which spaces a chain can mutate.
 */
case object SwitchModelTool extends TypedTool[SwitchModelInput](
  name = ToolName("switch_model"),
  description =
    """Switch the AI model or provider strategy used for this conversation. Accepts:
      |  - a model id (e.g. "anthropic/claude-opus-4-7", "openai/gpt-5.4") — creates an ad-hoc
      |    single-model override and assigns it
      |  - a saved strategy label or id — assigns that strategy
      |  - "auto" or "default" — reverts to the agent's pinned model
      |
      |Ambiguous matches return a disambiguation list; pair with `respond_options` to surface
      |the choices to the user.""".stripMargin,
  examples = List(
    ToolExample("Switch to a specific model", SwitchModelInput("anthropic/claude-opus-4-7")),
    ToolExample("Use a saved strategy by label", SwitchModelInput("Balanced")),
    ToolExample("Revert to default", SwitchModelInput("auto"))
  ),
  keywords = Set("switch", "model", "strategy", "provider", "change", "use", "auto", "default")
) {

  override protected def executeTyped(input: SwitchModelInput, ctx: TurnContext): Stream[Event] =
    Stream.force(handle(input.query.trim, ctx).map(emit => Stream.emit[Event](emit)))

  private def handle(rawQuery: String, ctx: TurnContext): Task[Message] = {
    val q = rawQuery.toLowerCase
    val convSpace = ctx.conversation.space
    if (q.isEmpty)
      Task.pure(reply(ctx, "switch_model: query is required (model id, strategy label, or 'auto')."))
    else if (q == "auto" || q == "default" || q == "automatic")
      ctx.sigil.unassignProviderStrategy(convSpace, ctx.chain).map { _ =>
        reply(ctx, "Reverted to automatic model selection — agent's pinned model will be used.")
      }
    else
      ctx.sigil.listProviderStrategies(convSpace, ctx.chain).flatMap { saved =>
        // Try strategy match first (id, then label). Then model-id ad-hoc.
        saved.find(_._id.value == rawQuery) match {
          case Some(byId) => assign(byId, ctx)
          case None =>
            saved.filter(_.label.equalsIgnoreCase(rawQuery)) match {
              case List(single) => assign(single, ctx)
              case multiple if multiple.nonEmpty =>
                Task.pure(disambiguateStrategies(rawQuery, multiple, ctx))
              case Nil =>
                fuzzyStrategyMatch(rawQuery, saved) match {
                  case List(single) => assign(single, ctx)
                  case multiple if multiple.nonEmpty =>
                    Task.pure(disambiguateStrategies(rawQuery, multiple, ctx))
                  case Nil =>
                    // Treat as a model-id ad-hoc switch.
                    createAdHoc(rawQuery, ctx)
                }
            }
        }
      }
  }

  private def assign(record: ProviderStrategyRecord, ctx: TurnContext): Task[Message] =
    ctx.sigil.assignProviderStrategy(ctx.conversation.space, record._id, ctx.chain).map { _ =>
      reply(ctx, s"Switched to strategy '${record.label}' (id=${record._id.value}).")
    }

  private def createAdHoc(modelIdRaw: String, ctx: TurnContext): Task[Message] = {
    val modelId: Id[Model] = Id(modelIdRaw)
    val record = ProviderStrategyRecord(
      space = ctx.conversation.space,
      label = s"Override: $modelIdRaw",
      defaultCandidates = List(ModelCandidate(modelId)),
      metadata = Map("kind" -> "ad-hoc-override", "createdBy" -> ctx.caller.value)
    )
    for {
      saved <- ctx.sigil.saveProviderStrategy(record)
      _     <- ctx.sigil.assignProviderStrategy(ctx.conversation.space, saved._id, ctx.chain)
    } yield reply(ctx, s"Switching to '$modelIdRaw'. The next message will use that model.")
  }

  private def fuzzyStrategyMatch(query: String,
                                 strategies: List[ProviderStrategyRecord]): List[ProviderStrategyRecord] = {
    val q = normalize(query)
    strategies.filter(s => normalize(s.label).contains(q) || s._id.value.equalsIgnoreCase(query))
  }

  private def disambiguateStrategies(query: String,
                                     options: List[ProviderStrategyRecord],
                                     ctx: TurnContext): Message = {
    val list = options.map(s => s"  - ${s.label} (id=${s._id.value})").mkString("\n")
    reply(ctx,
      s"Multiple strategies match '$query':\n$list\n\n" +
        "Re-run `switch_model` with the exact label or id, or pair with `respond_options` to ask the user.")
  }

  private def reply(ctx: TurnContext, text: String): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(text)),
    state          = EventState.Complete,
    role           = MessageRole.Tool,
    visibility     = MessageVisibility.Agents
  )

  private def normalize(s: String): String = s.toLowerCase.replaceAll("[\\s\\-_.]", "")
}
