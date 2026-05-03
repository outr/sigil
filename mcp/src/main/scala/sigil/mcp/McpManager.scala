package sigil.mcp

import fabric.Json
import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB
import sigil.participant.ParticipantId

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Lifecycle owner for [[McpClient]] connections. Per Sigil instance.
 *
 *   - **Lazy connect.** Clients are created on first use; the
 *     manager loads [[McpServerConfig]]s from
 *     [[McpCollections.mcpServers]] when asked for tools/clients.
 *   - **Idle timeout.** A background reaper closes connections that
 *     have been idle longer than the per-config
 *     `idleTimeoutMs`. Next use lazily reconnects (which also
 *     re-discovers tools).
 *   - **Periodic refresh.** While a connection is active, tool
 *     /resource/prompt lists are re-fetched every
 *     `refreshIntervalMs`.
 *   - **Cancellation tracking.** Each in-flight tool call is
 *     registered against the calling agent's id; on a `Stop` event
 *     for that agent the manager sends `notifications/cancelled`
 *     for every matching wire-level request.
 *
 * The manager is only useful when the host's `SigilDB` mixes in
 * [[McpCollections]]. Apps wire it up via [[McpSigil]].
 */
final class McpManager(sigil: Sigil { type DB <: SigilDB & McpCollections },
                       samplingHandlerFor: McpServerConfig => SamplingHandler) {

  private val clients         = new ConcurrentHashMap[String, ClientEntry]()
  private val toolCache       = new ConcurrentHashMap[String, CachedTools]()
  private val resourceCache   = new ConcurrentHashMap[String, CachedResources]()
  private val promptCache     = new ConcurrentHashMap[String, CachedPrompts]()
  private val inFlight        = new ConcurrentHashMap[ParticipantId, ConcurrentHashMap[(String, Long), Boolean]]()
  @volatile private var reaperStarted: Boolean = false

  /** All currently-configured server configs from the DB. */
  def listConfigs(): Task[List[McpServerConfig]] =
    sigil.withDB(_.mcpServers.transaction(_.list))

  /** Persist a new server config. Connects lazily on first use. */
  def addConfig(config: McpServerConfig): Task[McpServerConfig] = {
    val withId = config.copy(_id = McpServerConfig.idFor(config.name))
    sigil.withDB(_.mcpServers.transaction(_.upsert(withId)))
  }

  /** Remove a server config and tear down any active connection. */
  def removeConfig(name: String): Task[Boolean] = {
    val id = McpServerConfig.idFor(name)
    sigil.withDB(_.mcpServers.transaction(_.delete(id))).flatMap(_ => closeClient(name)).map(_ => true)
  }

  /** Force-disconnect; next use will reconnect and re-discover. */
  def closeClient(name: String): Task[Unit] = Task.defer {
    Option(clients.remove(name)) match {
      case Some(entry) =>
        toolCache.remove(name); resourceCache.remove(name); promptCache.remove(name)
        entry.client.close()
      case None => Task.unit
    }
  }

  /** Force-refresh the cached tool list for `name` regardless of staleness. */
  def refresh(name: String): Task[List[McpToolDefinition]] = Task.defer {
    toolCache.remove(name)
    resourceCache.remove(name)
    promptCache.remove(name)
    listTools(name)
  }

  /** Try connecting to `name` and return success/failure with details. */
  def test(name: String): Task[Either[Throwable, List[McpToolDefinition]]] =
    listTools(name).map(Right(_)).handleError(t => Task.pure(Left(t)))

  /** Tool definitions across all configured servers, with the
    * server's `prefix` already applied to each name. */
  def allToolsByDisplayName: Task[Map[String, (McpServerConfig, McpToolDefinition)]] = listConfigs().flatMap { configs =>
    Task.sequence(configs.map { cfg =>
      listTools(cfg.name).map(_.map(td => (cfg.prefix.getOrElse("") + td.name) -> (cfg, td))).handleError { t =>
        Task {
          scribe.warn(s"MCP: failed to list tools for ${cfg.name}: ${t.getMessage}")
          Nil
        }
      }
    }).map(_.flatten.toMap)
  }

  /** Tool definitions for a single server (cache-first). */
  def listTools(name: String): Task[List[McpToolDefinition]] =
    cachedOrFetch(toolCache, name, refreshKind = "tools",
      fetch = c => c.listTools(),
      wrap  = (now, list) => CachedTools(now, list))
      .map(_.tools)

  def listResources(name: String): Task[List[McpResource]] =
    cachedOrFetch(resourceCache, name, refreshKind = "resources",
      fetch = c => c.listResources(),
      wrap  = (now, list) => CachedResources(now, list))
      .map(_.resources)

  def readResource(name: String, uri: String): Task[Json] = clientFor(name).flatMap { client =>
    touch(name)
    client.readResource(uri)
  }

  def listPrompts(name: String): Task[List[McpPrompt]] =
    cachedOrFetch(promptCache, name, refreshKind = "prompts",
      fetch = c => c.listPrompts(),
      wrap  = (now, list) => CachedPrompts(now, list))
      .map(_.prompts)

  def getPrompt(name: String, prompt: String, args: Map[String, String] = Map.empty): Task[Json] = clientFor(name).flatMap { client =>
    touch(name)
    client.getPrompt(prompt, args)
  }

  /** Invoke a tool, registering the call against the agent for
    * cancellation tracking. The manager touches the connection's
    * idle timer and de-registers the call on completion. */
  def callTool(serverName: String,
               toolName: String,
               arguments: Json,
               agentId: ParticipantId): Task[Json] =
    clientFor(serverName).flatMap { client =>
      touch(serverName)
      val agentMap = inFlight.computeIfAbsent(agentId, _ => new ConcurrentHashMap[(String, Long), Boolean]())
      val wireRef = new AtomicLong(-1L)
      client.callTool(toolName, arguments, wireId => {
        wireRef.set(wireId)
        agentMap.put((serverName, wireId), true)
      })
        .guarantee(Task {
          val wid = wireRef.get()
          if (wid >= 0) agentMap.remove((serverName, wid))
          if (agentMap.isEmpty) inFlight.remove(agentId)
          touch(serverName)
        })
    }

  /** Cancel every in-flight tool call owned by `agentId`. Called
    * by the framework on `Stop` events targeting that agent. */
  def cancelInFlight(agentId: ParticipantId, reason: Option[String] = None): Task[Unit] = Task.defer {
    Option(inFlight.remove(agentId)) match {
      case None => Task.unit
      case Some(map) =>
        val tasks = map.keySet().asScala.toList.map { case (server, wireId) =>
          Option(clients.get(server)) match {
            case Some(entry) => entry.client.cancelRequest(wireId, reason).handleError(_ => Task.unit)
            case None        => Task.unit
          }
        }
        Task.sequence(tasks).unit
    }
  }

  /** Close every active connection. Called by Sigil shutdown. */
  def closeAll(): Task[Unit] = Task.defer {
    val all = clients.values().asScala.toList
    clients.clear()
    Task.sequence(all.map(_.client.close().handleError(_ => Task.unit))).unit
  }

  /**
   * Test/debug helper: install a pre-constructed [[McpClient]] under
   * `name`, bypassing persistence and lazy-connect. Production code
   * persists a config via [[addConfig]] and lets [[clientFor]] manage
   * the lifecycle.
   */
  def registerClientForTesting(name: String, client: McpClient): Unit = {
    clients.put(name, ClientEntry(client, new AtomicLong(System.currentTimeMillis())))
  }

  /** Test helper: register an in-flight call against an agent without
    * actually invoking a tool. Used by cancellation tests. */
  def registerInFlightForTesting(agentId: ParticipantId, serverName: String, wireId: Long): Unit = {
    val map = inFlight.computeIfAbsent(agentId, _ => new ConcurrentHashMap[(String, Long), Boolean]())
    map.put((serverName, wireId), true)
  }

  private def clientFor(name: String): Task[McpClient] = Task.defer {
    Option(clients.get(name)) match {
      case Some(entry) =>
        Task.pure(entry.client)
      case None =>
        sigil.withDB(_.mcpServers.transaction(_.get(McpServerConfig.idFor(name)))).flatMap {
          case None => Task.error(new McpError(-1, s"MCP server '$name' not configured"))
          case Some(cfg) => connectAndRegister(cfg)
        }
    }
  }

  private def connectAndRegister(cfg: McpServerConfig): Task[McpClient] = Task.defer {
    val notificationListener: (String, fabric.Json) => Task[Unit] = (method, _) => Task {
      // Invalidate the relevant cache slice when the server signals it.
      method match {
        case "notifications/tools/list_changed"     => toolCache.remove(cfg.name)
        case "notifications/resources/list_changed" => resourceCache.remove(cfg.name)
        case "notifications/prompts/list_changed"   => promptCache.remove(cfg.name)
        case _                                      => ()
      }
    }
    val client: McpClient = cfg.transport match {
      case _: McpTransport.Stdio   => new StdioMcpClient(cfg, samplingHandlerFor(cfg), notificationListener)
      case _: McpTransport.HttpSse => new HttpSseMcpClient(cfg, samplingHandlerFor(cfg), notificationListener)
    }
    val entry = ClientEntry(client, new AtomicLong(System.currentTimeMillis()))
    clients.put(cfg.name, entry)
    ensureReaperStarted()
    client.start().map(_ => client).handleError { t =>
      clients.remove(cfg.name)
      Task.error(t)
    }
  }

  private def cachedOrFetch[K, V](cache: ConcurrentHashMap[String, V],
                                   name: String,
                                   refreshKind: String,
                                   fetch: McpClient => Task[K],
                                   wrap: (Long, K) => V): Task[V] = Task.defer {
    val cfg = clientConfigFor(name)
    cfg.flatMap { cfg =>
      val now = System.currentTimeMillis()
      val cached = Option(cache.get(name)).asInstanceOf[Option[Cached[K]]]
      if (cached.exists(c => now - c.fetchedAtMs < cfg.refreshIntervalMs)) {
        Task.pure(cached.get.asInstanceOf[V])
      } else {
        clientFor(name).flatMap { client =>
          touch(name)
          fetch(client).map { list =>
            val v = wrap(now, list)
            cache.put(name, v)
            v
          }
        }
      }
    }
  }

  private def clientConfigFor(name: String): Task[McpServerConfig] =
    sigil.withDB(_.mcpServers.transaction(_.get(McpServerConfig.idFor(name)))).flatMap {
      case None => Task.error(new McpError(-1, s"MCP server '$name' not configured"))
      case Some(cfg) => Task.pure(cfg)
    }

  private def touch(name: String): Unit = {
    val entry = clients.get(name)
    if (entry != null) entry.lastUsedMs.set(System.currentTimeMillis())
  }

  private def ensureReaperStarted(): Unit = synchronized {
    if (!reaperStarted) {
      reaperStarted = true
      reaperLoop().startUnit()
    }
  }

  private def reaperLoop(): Task[Unit] = Task.defer {
    val now = System.currentTimeMillis()
    val entries = clients.entrySet().asScala.toList.map(e => (e.getKey, e.getValue))
    Task.sequence(entries.map { case (name, entry) =>
      sigil.withDB(_.mcpServers.transaction(_.get(McpServerConfig.idFor(name)))).map {
        case Some(cfg) if now - entry.lastUsedMs.get() > cfg.idleTimeoutMs => Some(name)
        case _ => None
      }
    }).map(_.flatten).flatMap { staleNames =>
      Task.sequence(staleNames.map(closeClient(_).handleError(_ => Task.unit)))
    }.flatMap(_ => Task.sleep(30.seconds)).flatMap(_ => reaperLoop())
  }

  // -- helper traits / cache shapes --
  private trait Cached[+T] { def fetchedAtMs: Long }
  private case class CachedTools(fetchedAtMs: Long, tools: List[McpToolDefinition]) extends Cached[List[McpToolDefinition]]
  private case class CachedResources(fetchedAtMs: Long, resources: List[McpResource]) extends Cached[List[McpResource]]
  private case class CachedPrompts(fetchedAtMs: Long, prompts: List[McpPrompt]) extends Cached[List[McpPrompt]]
  private case class ClientEntry(client: McpClient, lastUsedMs: AtomicLong)
}
