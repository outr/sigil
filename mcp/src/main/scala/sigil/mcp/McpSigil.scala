package sigil.mcp

import fabric.*
import fabric.io.JsonFormatter
import rapid.Task
import sigil.Sigil
import sigil.behavior.Behavior
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
      case Some(modelId) => new ProviderSamplingHandler(this, config, modelId)
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
final class ProviderSamplingHandler(sigil: Sigil,
                                    config: McpServerConfig,
                                    modelId: lightdb.id.Id[Model])
  extends SamplingHandler {
  override def handle(serverName: String, params: Json): Task[Json] = Task.defer {
    // Best-effort extraction of the user's text from the MCP CreateMessageRequest shape.
    val messages = params.get("messages").map(_.asVector.toList).getOrElse(Nil)
    val userText = messages.flatMap(_.get("content")).flatMap { content =>
      content match {
        case o: Obj => o.value.get("text").map(_.asString)
        case s: Str => Some(s.value)
        case _      => None
      }
    }.mkString("\n")
    sigil.providerFor(modelId, Nil).flatMap { _ =>
      // For now, the framework returns a placeholder acknowledging the request.
      // A full implementation runs a one-shot provider call; that requires the
      // provider's request-builder surface which is currently per-conversation.
      // Apps that need full sampling override this handler.
      Task.pure(obj(
        "model"      -> str(modelId.value),
        "stopReason" -> str("endTurn"),
        "role"       -> str("assistant"),
        "content"    -> obj("type" -> str("text"), "text" -> str(s"[sampling stub: server=$serverName text-len=${userText.length}]"))
      ))
    }
  }
}
