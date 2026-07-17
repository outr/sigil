package sigil.metals

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.db.SigilDB
import sigil.mcp.{McpCollections, McpSigil}
import sigil.tool.Tool

import java.nio.file.Path

/**
 * Sigil refinement for apps that include the `sigil-metals` module.
 *
 * Metals (the Scala LSP) optionally exposes its developer-tooling
 * surface as an MCP server. Mixed in, this trait owns the lifecycle
 * of a Metals subprocess per workspace, watches `.metals/mcp.json`
 * for the chosen port, and inserts an [[sigil.mcp.McpServerConfig]]
 * into the DB so [[sigil.mcp.McpManager]] picks the connection up
 * through the existing flow — agents discover Metals' tools
 * (`find-symbol`, `compile`, `test`, …) via `find_capability` like
 * any other MCP-bridged tool, no special integration code on the
 * agent side.
 *
 * Apps mix this in alongside [[McpSigil]] (transitively required —
 * the trait extends it) and override [[metalsWorkspace]] to map a
 * conversation id to the workspace path Metals should index. Every
 * other knob has a sensible default; the consumer wiring is three
 * lines plus the workspace mapping.
 *
 * Subprocesses are cleaned up via [[Sigil.onShutdown]] so orderly
 * teardowns (CLI / test harnesses) leave nothing behind. A JVM
 * shutdown hook covers the catastrophic case (process killed
 * without [[Sigil.shutdown]] running).
 */
trait MetalsSigil extends Sigil with McpSigil {
  type DB <: SigilDB & McpCollections

  /**
   * Map a conversation id to the workspace path Metals should
   * index for that conversation. Return `None` to skip Metals for
   * the conversation entirely (no subprocess spawned, no tools
   * surfaced).
   *
   * Default: delegates to [[Sigil.workspaceFor]]. Apps that
   * implement the framework workspace hook get Metals routing for
   * free; apps that want to gate Metals separately (e.g. only
   * spawn for explicitly-Scala projects) override this directly.
   *
   * Multiple conversations resolving to the same workspace path
   * share one Metals subprocess (keyed by canonical absolute
   * path). The mapping is opaque to the manager; whatever the app
   * returns, that's the `cwd` Metals gets.
   */
  def metalsWorkspace(conversationId: Id[Conversation]): Task[Option[Path]] =
    workspaceFor(conversationId)

  /**
   * Command + args used to launch Metals. Default: `metals` on
   * `PATH`. Apps override for Coursier-installed paths
   * (`coursier launch org.scalameta:metals_2.13:1.4.x`), pinned
   * MCP ports, custom JVM args, or sandbox launchers.
   */
  def metalsLauncher: List[String] = List("metals")

  /**
   * Idle timeout after which an inactive Metals subprocess gets
   * torn down. Defaults to 15 minutes — Metals is heavyweight
   * (~700MB-1GB RAM); reaping idle sessions matters when an app
   * has many conversations. The manager spawns lazily on next
   * use so apps don't pay the cold-start hit on every turn.
   */
  def metalsIdleTimeoutMs: Long = 15L * 60L * 1000L

  /**
   * Build the [[org.eclipse.lsp4j.services.LanguageClient]] used to
   * drive the Metals subprocess. Default returns a
   * [[MetalsLanguageClient]] which auto-responds "Import build" to
   * sbt-detection prompts and routes `window/logMessage` /
   * `window/showMessage` into the supplied per-line callback (so
   * Metals' streaming progress reaches the chat chip via
   * [[sigil.event.ToolLog]] events).
   *
   * Apps that want different behavior (custom action choice for
   * non-sbt workspaces, additional capability declarations, etc.)
   * subclass [[MetalsLanguageClient]] and override here.
   */
  def metalsLanguageClient(label: String,
                           onLogLine: java.util.concurrent.atomic.AtomicReference[
                             Option[String => rapid.Task[Unit]]
                           ],
                           onStatus: java.util.concurrent.atomic.AtomicReference[
                             Option[String => rapid.Task[Unit]]
                           ] = new java.util.concurrent.atomic.AtomicReference(None)): org.eclipse.lsp4j.services.LanguageClient =
    new MetalsLanguageClient(label, onLogLine, onStatus)

  /**
   * The single per-Sigil Metals manager. Lazy so the reaper /
   * watcher fibers only spin up if a Metals-touching code path
   * actually accesses it.
   */
  final lazy val metalsManager: MetalsManager = new MetalsManager(this)

  /**
   * Sigil bug #88 — when Metals starts for a workspace, also write a
   * `LspServerConfig("scala", ...)` so the framework's generic
   * `lsp_*` family (lsp_diagnostics, lsp_definitions, …) finds
   * Metals as their backend. Without this, agents that go through
   * generic LSP tools instead of Metals' MCP-bridged tools hit
   * "No LspServerConfig persisted for language 'scala'" and the
   * call fails.
   *
   * Runtime no-op when the app's `DB` doesn't mix in
   * [[sigil.tooling.ToolingCollections]] — apps that use Metals
   * via MCP only (no generic LSP surface) pay nothing. Apps that
   * mix ToolingSigil get the auto-write for free.
   */
  def writeLspServerConfigForMetals(workspace: java.nio.file.Path): rapid.Task[Unit] = {
    val config = sigil.tooling.LspServerConfig(
      languageId = "scala",
      command = metalsLauncher.headOption.getOrElse("metals"),
      args = metalsLauncher.drop(1),
      rootMarkers = List("build.sbt", "build.sc", "pom.xml"),
      fileGlobs = List("**/*.scala", "**/*.sbt", "**/*.sc"),
      idleTimeoutMs = metalsIdleTimeoutMs,
      _id = sigil.tooling.LspServerConfig.idFor("scala")
    )
    withDB { db =>
      db match {
        case tc: sigil.tooling.ToolingCollections =>
          tc.lspServers.transaction(_.upsert(config)).map(_ => ())
        case _ =>
          rapid.Task.unit
      }
    }
  }

  /**
   * Sigil bug #85 — when Metals is running for the conversation's
   * workspace, surface `lsp` + `bsp` toolchains so
   * [[sigil.Sigil.findCapabilities]] boosts LSP / BSP tools above
   * generic verbs (grep, glob, execute_script) for inspection-
   * shaped queries. Apps with workspaces NOT bound to Metals
   * fall through to the framework default `Set.empty`.
   *
   * Resolves the workspace via [[metalsWorkspace]]; checks
   * [[MetalsManager.status]] for an alive process whose endpoint
   * is registered. Falls through with `Set.empty` if none of
   * those resolve — the boost is opt-in by Metals being
   * actually present.
   */
  override def activeToolchains(conversationId: lightdb.id.Id[Conversation]): rapid.Task[Set[String]] =
    metalsWorkspace(conversationId).flatMap {
      case None => super.activeToolchains(conversationId)
      case Some(workspace) =>
        metalsManager.status.map { entries =>
          val canonical = workspace.toAbsolutePath.normalize
          val active = entries.exists(e => e.workspace == canonical && e.alive && e.endpoint.isDefined)
          if (active) Set("lsp", "bsp") else Set.empty[String]
        }
    }

  /**
   * Hook into Sigil's static-tool list so the lifecycle tools
   * are discoverable via the standard `find_capability` flow.
   * Apps that don't want them surfaced override
   * [[metalsToolsEnabled]] to false.
   */
  override def staticTools: List[Tool] = {
    val base = super.staticTools
    if (metalsToolsEnabled) base ++ metalsTools else base
  }

  /**
   * Whether the framework's three Metals lifecycle tools
   * (`start_metals`, `stop_metals`, `metals_status`) are appended
   * to `staticTools`. Default true. Apps that prefer to gate
   * Metals behind app-specific affordances override to false.
   */
  def metalsToolsEnabled: Boolean = true

  protected def metalsTools: List[Tool] = List(
    new StartMetalsTool,
    new StopMetalsTool,
    new MetalsStatusTool
  )

  /**
   * Tear down every spawned Metals subprocess on Sigil shutdown.
   * Chains through `super.onShutdown` so apps that mix in multiple
   * modules tear each down in declaration order.
   */
  override protected def onShutdown: Task[Unit] =
    metalsManager.shutdown.flatMap(_ => super.onShutdown)
}
