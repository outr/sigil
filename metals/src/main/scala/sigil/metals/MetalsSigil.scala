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
   * The single per-Sigil Metals manager. Lazy so the reaper /
   * watcher fibers only spin up if a Metals-touching code path
   * actually accesses it.
   */
  final lazy val metalsManager: MetalsManager = new MetalsManager(this)

  /** Hook into Sigil's static-tool list so the lifecycle tools
    * are discoverable via the standard `find_capability` flow.
    * Apps that don't want them surfaced override
    * [[metalsToolsEnabled]] to false. */
  override def staticTools: List[Tool] = {
    val base = super.staticTools
    if (metalsToolsEnabled) base ++ metalsTools else base
  }

  /** Whether the framework's three Metals lifecycle tools
    * (`start_metals`, `stop_metals`, `metals_status`) are appended
    * to `staticTools`. Default true. Apps that prefer to gate
    * Metals behind app-specific affordances override to false. */
  def metalsToolsEnabled: Boolean = true

  protected def metalsTools: List[Tool] = List(
    new StartMetalsTool,
    new StopMetalsTool,
    new MetalsStatusTool
  )

  /** Tear down every spawned Metals subprocess on Sigil shutdown.
    * Chains through `super.onShutdown` so apps that mix in multiple
    * modules tear each down in declaration order. */
  override protected def onShutdown: Task[Unit] =
    metalsManager.shutdown.flatMap(_ => super.onShutdown)
}
