package sigil.mcp

import fabric.*
import fabric.io.JsonFormatter
import rapid.Task
import sigil.Sigil
import sigil.role.Role
import sigil.db.{Model, SigilDB}
import sigil.event.MessageVisibility
import sigil.tool.Tool

/**
 * Sigil refinement for apps that include the `sigil-mcp` module.
 *
 * Constrains `type DB` to a [[SigilDB]] subclass mixing in
 * [[McpCollections]] (so `db.mcpServers` is reachable), exposes a
 * [[McpManager]] hook backed by the persisted server registry, and
 * — when [[mcpManagementToolsEnabled]] is true (the default) — adds
 * the MCP runtime-management tools to `staticTools` (`add_mcp_server`,
 * `list_mcp_servers`, `remove_mcp_server`, `test_mcp_server`,
 * `refresh_mcp_server`, `read_mcp_resource`, `list_mcp_prompts`,
 * `get_mcp_prompt`).
 *
 * The resolver hook [[samplingHandlerFor]] returns a per-server
 * [[SamplingHandler]] — the framework default uses the host's
 * provider via [[Sigil.providerFor]] when a config has
 * `samplingModelId`, refusing otherwise. Apps that want different
 * sampling semantics override this hook.
 *
 * Cancellation wiring is automatic: on init the framework drains
 * `Sigil.signals` for `Stop` events targeting an agent and
 * propagates them through [[McpManager.cancelInFlight]] to send
 * `notifications/cancelled` for any MCP calls the agent has in
 * flight.
 */
trait McpSigil extends Sigil {
  type DB <: SigilDB & McpCollections

  /** Whether the runtime-management tools (add / list / remove /
    * test / refresh / read-resource / list-prompts / get-prompt)
    * are appended to `staticTools`. Default true. Apps that want a
    * locked-down agent surface override to false. */
  def mcpManagementToolsEnabled: Boolean = true

  /** Per-server sampling-handler resolver. Default: refuse unless
    * `samplingModelId` is set, then delegate via [[providerFor]]. */
  protected def samplingHandlerFor(config: McpServerConfig): SamplingHandler =
    config.samplingModelId match {
      case None => SamplingHandler.Refusing
      case Some(modelId) => new ProviderSamplingHandler(this, config, modelId.asInstanceOf[lightdb.id.Id[sigil.db.Model]])
    }

  /** The single per-Sigil MCP manager. Lazy so it only spins up when
    * MCP-touching code accesses it. */
  final lazy val mcpManager: McpManager =
    new McpManager(this.asInstanceOf[Sigil { type DB <: SigilDB & McpCollections }], samplingHandlerFor)

  /** Tool finder that surfaces all MCP-server-side tools. Apps that
    * want to chain it with other finders compose at the
    * `findTools` override site. */
  final lazy val mcpToolFinder: McpToolFinder = new McpToolFinder(mcpManager)

  /** Hook into Sigil's static-tool list. Appends the runtime-
    * management tools when [[mcpManagementToolsEnabled]] is true. */
  override def staticTools: List[Tool] = {
    val base = super.staticTools
    if (mcpManagementToolsEnabled) base ++ mcpManagementTools else base
  }

  /** Register [[McpKind]] so [[McpTool]] records' `kind` field
    * round-trips through fabric's polymorphic [[sigil.tool.ToolKind]]
    * discriminator. */
  override protected def toolKindRegistrations: List[fabric.rw.RW[? <: sigil.tool.ToolKind]] =
    fabric.rw.RW.static[sigil.tool.ToolKind](McpKind) :: super.toolKindRegistrations

  protected def mcpManagementTools: List[Tool] = List(
    new AddMcpServerTool(mcpManager),
    new ListMcpServersTool(mcpManager),
    new RemoveMcpServerTool(mcpManager),
    new TestMcpServerTool(mcpManager),
    new RefreshMcpServerTool(mcpManager),
    new ReadMcpResourceTool(mcpManager),
    new ListMcpPromptsTool(mcpManager),
    new GetMcpPromptTool(mcpManager)
  )

  /** Sigil init hook — subscribe to Stop events and cancel in-flight
    * MCP calls for the targeted agent. The fiber lives for the
    * Sigil's lifetime. */
  protected def startMcpCancellationListener(): Task[Unit] = Task {
    import sigil.event.Stop
    signals
      .collect { case s: Stop => s }
      .evalMap { stop =>
        stop.targetParticipantId match {
          case Some(target) => mcpManager.cancelInFlight(target, stop.reason).handleError(_ => Task.unit)
          case None => Task.unit
        }
      }
      .drain
      .startUnit()
    ()
  }

  // Trigger the listener once at trait init.
  startMcpCancellationListener().sync()
}

/**
 * Default [[SamplingHandler]] that delegates to
 * [[Sigil.providerFor]] using the per-server `samplingModelId`. The
 * server's `params` are mapped 1:1 onto a single-shot provider call
 * and the response is wrapped in MCP's `CreateMessageResult` shape.
 *
 * This is intentionally a thin pass-through. Apps with more nuanced
 * sampling needs (token-budget enforcement, system-prompt overlays,
 * model fallback) override `McpSigil.samplingHandlerFor` with their
 * own implementation.
 */
/**
 * Real [[SamplingHandler]] backed by [[Sigil.providerFor]] +
 * [[OneShotRequest]]. The MCP server's `CreateMessageRequest` is
 * translated into:
 *
 *   - `systemPrompt` from `params.systemPrompt` when present
 *     (default empty)
 *   - `userPrompt` assembled from `params.messages` — each
 *     message is rendered as `[role] text` and joined with newlines;
 *     non-text content blocks are dropped (we only support text
 *     sampling for now)
 *   - `temperature` and `maxTokens` carried through from the
 *     request when supplied
 *
 * The provider's [[ProviderEvent]] stream is collapsed by
 * accumulating every `TextDelta` and `ContentBlockDelta` until
 * `Done` (or `Error`). The result is wrapped in MCP's
 * `CreateMessageResult` shape.
 */
final class ProviderSamplingHandler(host: Sigil,
                                    config: McpServerConfig,
                                    modelId: lightdb.id.Id[Model])
  extends SamplingHandler {
  import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent, StopReason}

  override def handle(serverName: String, params: Json): Task[Json] = Task.defer {
    val systemPrompt = params.get("systemPrompt").map(_.asString).getOrElse("")
    val userPrompt   = renderUserPrompt(params.get("messages").map(_.asVector.toList).getOrElse(Nil))
    val temperature  = params.get("temperature").map(_.asDouble)
    val maxTokensOpt = params.get("maxTokens").map(_.asInt)
    val settings     = GenerationSettings(temperature = temperature, maxOutputTokens = maxTokensOpt)

    val request = OneShotRequest(
      modelId            = modelId,
      systemPrompt       = systemPrompt,
      userPrompt         = userPrompt,
      generationSettings = settings
    )

    host.providerFor(modelId, Nil).flatMap { provider =>
      val collected = new java.lang.StringBuilder()
      val stopRef   = new java.util.concurrent.atomic.AtomicReference[StopReason](StopReason.Complete)
      val errRef    = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)

      provider(request).evalMap {
        case ProviderEvent.TextDelta(t)             => Task { collected.append(t); () }
        case ProviderEvent.ContentBlockDelta(_, t)  => Task { collected.append(t); () }
        case ProviderEvent.Done(reason)             => Task { stopRef.set(reason); () }
        case ProviderEvent.Error(msg)               => Task { errRef.set(Some(msg)); () }
        case _                                      => Task.unit
      }.drain.map { _ =>
        errRef.get() match {
          case Some(msg) => throw new McpError(-1, s"Sampling failed: $msg")
          case None =>
            obj(
              "model"      -> str(modelId.value),
              "stopReason" -> str(stopReasonString(stopRef.get())),
              "role"       -> str("assistant"),
              "content"    -> obj("type" -> str("text"), "text" -> str(collected.toString))
            )
        }
      }
    }
  }

  private def renderUserPrompt(messages: List[Json]): String =
    messages.flatMap { m =>
      val role = m.get("role").map(_.asString).getOrElse("user")
      val text = m.get("content") match {
        case Some(o: Obj) => o.value.get("text").map(_.asString).orElse(o.value.get("type").map(_.asString))
        case Some(s: Str) => Some(s.value)
        case _ => None
      }
      text.map(t => s"[$role] $t")
    }.mkString("\n")

  private def stopReasonString(s: StopReason): String = s match {
    case StopReason.Complete        => "endTurn"
    case StopReason.ToolCall        => "toolUse"
    case StopReason.MaxTokens       => "maxTokens"
    case StopReason.ContentFiltered => "stopSequence"
    case StopReason.Cancelled       => "endTurn"
  }
}
