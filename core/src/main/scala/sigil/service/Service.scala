package sigil.service

import lightdb.id.Id
import sigil.tool.ToolName

/**
 * A long-lived background dependency the framework surfaces to UIs
 * via persistent status chips with state controls and (optionally)
 * streaming logs. Concrete examples: a running LSP / BSP server, a
 * llama.cpp model server, an MCP server connection, a database pool.
 *
 * Apps register services via [[sigil.Sigil.services]]; each registered
 * service is reachable through [[sigil.Sigil.serviceById]]. The
 * framework caches the most recent
 * [[sigil.signal.ServiceStatusSignal]] per service id and replays it
 * to fresh subscribers on connect, so a newly-opened client tab sees
 * the current state of every service without the service having to
 * re-emit.
 *
 * Authoring is intentionally minimal — `id` and `name` are the only
 * required members; everything else has sensible defaults. A service
 * whose state never changes after startup (a passive resource the
 * framework just needs to advertise) implements only `id` / `name` /
 * `kind` and emits one `ServiceStatusSignal(state = Up)` after init.
 *
 * `id` is stable for the lifetime of the service across process
 * restarts (e.g. `Id[Service](s"metals.$workspaceHash")`). The cache
 * key is `id`; a service whose id changes across restarts will not
 * shed its prior cached state and may surface a stale chip to fresh
 * clients until the new id's first emission lands.
 *
 * `controls` lists the [[ToolName]]s the chip's menu should surface
 * for this service (restart, pause, resume, view logs, etc.). The
 * framework does not invoke these — it just advertises the names so
 * the client can render them; the user clicking a control fires the
 * named tool through the normal agent dispatch path.
 *
 * `hasStreamingLog` advertises whether the service emits
 * [[sigil.signal.ServiceLogSignal]]s a chip-attached log tail should
 * subscribe to. `logFilePath` is the on-disk path of a persistent log
 * file the service writes (when it does); clients that want to
 * inspect history beyond the live tail can read it directly.
 */
trait Service {
  /** Stable, process-restart-safe identifier. The cache key for
    * [[sigil.signal.ServiceStatusSignal]] latest-state replay. */
  def id: Id[Service]

  /** Display name for the chip. */
  def name: String

  /** Broad category — informs chip iconography / grouping. Default
    * [[ServiceKind.Other]] with the service's `name` so apps that
    * don't think about category at all still get a sensible label. */
  def kind: ServiceKind = ServiceKind.Other(name)

  /** Tool names surfaced as chip menu items. Default empty — the chip
    * just renders the status, no actions. */
  def controls: List[ToolName] = Nil

  /** When true, clients with a tail-attached log viewer should
    * subscribe to [[sigil.signal.ServiceLogSignal]]s keyed to
    * `id`. Default false — services that don't surface streaming
    * logs don't get a log tab. */
  def hasStreamingLog: Boolean = false

  /** Optional path to an on-disk log file. Clients with file-system
    * access can read it for history beyond the live tail. */
  def logFilePath: Option[String] = None

  /** Current state as reported by whichever subsystem owns the
    * service. Read by the framework when a fresh client connects but
    * the latest-status cache has no entry yet (the service has never
    * emitted a status signal in this process lifetime). Implementations
    * derive this from observable signals — for [[sigil.provider.Provider]]
    * that's rate-limiter / capacity-gate availability; for an
    * external subprocess it's `process.isAlive` plus the connection
    * handshake state.
    *
    * Default [[ServiceState.Up]] so passive services that don't
    * model lifecycle don't have to override. Services with real
    * lifecycle override to compute from internal state. */
  def currentState: ServiceState = ServiceState.Up
}

object Service
