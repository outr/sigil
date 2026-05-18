package sigil.metals

import com.google.gson.JsonObject
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient
import rapid.Task

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/**
 * `LanguageClient` Sigil installs against the Metals subprocess. Two
 * jobs:
 *
 *  1. **Auto-respond to `window/showMessageRequest`** so Metals doesn't
 *     sit forever waiting for a human (sigil bug #70). Real Metals
 *     fires this when it detects a build tool — "New sbt workspace
 *     detected, would you like to import the build?" with actions
 *     `[Import build, Not now, Don't show again]`. Without a response,
 *     Metals never runs `bloopInstall`, never indexes, never writes
 *     `.metals/mcp.json`. The default action is "Import build" (the
 *     first one) so build setup proceeds; apps that want different
 *     behavior subclass.
 *
 *  2. **Route `window/logMessage` + `window/showMessage` into the
 *     `onLogLine` callback** so Metals' streaming progress reaches
 *     the chat chip via [[sigil.event.ToolLog]] events (sigil bug
 *     #69). The callback is supplied per-spawn by [[MetalsManager]]
 *     and updated on idempotent re-attaches.
 *
 * Other LSP requests/notifications are no-ops — Sigil isn't running
 * a real editor; we don't render diagnostics, code lenses, etc. Apps
 * that want them subclass [[MetalsLanguageClient]] and override the
 * relevant methods.
 */
final class MetalsLanguageClient(label: String,
                                 onLogLine: AtomicReference[Option[String => Task[Unit]]],
                                 onStatus: AtomicReference[Option[String => Task[Unit]]] =
                                   new AtomicReference(None))
  extends LanguageClient {

  /**
   * Action title to pick when Metals fires `showMessageRequest`.
   * Restricted to known-safe initialisation actions — picking the
   * first action blindly opens browsers (Metals Doctor: "More
   * information") and starts ancillary HTTP servers ("Http server
   * is required ... Start") that have nothing to do with importing
   * the build. Returns `null` for prompts that lack a recognised
   * action so Metals dismisses them quietly.
   *
   * Apps that want a different policy subclass and override.
   */
  protected def preferredAction(params: ShowMessageRequestParams): Option[String] = {
    val actions = Option(params.getActions).map(_.asScala.toList.map(_.getTitle)).getOrElse(Nil)
    actions.find(MetalsLanguageClient.SafeAutoResponseTitles.contains)
  }

  override def showMessageRequest(params: ShowMessageRequestParams): CompletableFuture[MessageActionItem] = {
    val msg = Option(params.getMessage).getOrElse("")
    preferredAction(params) match {
      case Some(title) =>
        val item = new MessageActionItem(title)
        scribe.info(s"[$label] showMessageRequest -> $title (message: $msg)")
        publishLine(s"[metals] $title (in response to: $msg)")
        CompletableFuture.completedFuture(item)
      case None =>
        scribe.info(s"[$label] showMessageRequest -> dismissed (message: $msg)")
        // Returning null is the LSP-spec way to say "no selection" /
        // "user dismissed". Metals then drops the prompt without
        // taking any of its actions. Avoids the browser-spam that
        // happens when "More information" is the first action.
        CompletableFuture.completedFuture(null)
    }
  }

  override def showMessage(params: MessageParams): Unit = {
    scribe.info(s"[$label] showMessage[${params.getType}] ${params.getMessage}")
    publishLine(s"[metals] ${params.getMessage}")
  }

  override def logMessage(params: MessageParams): Unit = {
    scribe.info(s"[$label] logMessage[${params.getType}] ${params.getMessage}")
    publishLine(params.getMessage)
  }

  override def telemetryEvent(params: Object): Unit = ()

  override def publishDiagnostics(params: PublishDiagnosticsParams): Unit = ()

  // Explicitly override the LSP `default` methods. Without these
  // overrides, lsp4j's `AnnotationUtil.findRpcMethods` reflection
  // scan throws "Duplicate RPC method workspace/configuration"
  // when our Scala class extends `LanguageClient` — the Scala
  // compiler emits synthetic forwarders alongside the inherited
  // defaults, and the scan double-counts.
  //
  // Metals requests this with section = "metals" right after
  // `initialized`. The response drives `UserConfiguration` —
  // crucially `start-mcp-server: true` is what makes Metals
  // start its MCP server and write `.metals/mcp.json`. Without
  // it, Metals indexes the workspace but never exposes the MCP
  // endpoint Sigil needs to connect to.
  override def configuration(params: ConfigurationParams): CompletableFuture[java.util.List[Object]] = {
    val items = Option(params.getItems).map(_.asScala.toList).getOrElse(Nil)
    val results: java.util.List[Object] = items.map { item =>
      Option(item.getSection) match {
        case Some("metals") => userConfigJson(): Object
        case _ => new JsonObject(): Object
      }
    }.asJava
    CompletableFuture.completedFuture(results)
  }

  /**
   * JSON config Metals reads via `workspace/configuration`.
   * `start-mcp-server: true` enables the MCP endpoint we need.
   * Subclasses override [[mcpClientName]] to identify which
   * client variant the on-disk `.metals/mcp.json` is tagged
   * with.
   */
  private def userConfigJson(): JsonObject = {
    val obj = new JsonObject()
    obj.addProperty("start-mcp-server", true)
    obj.addProperty("mcp-client", mcpClientName)
    obj
  }

  /**
   * Identifier Metals stamps into `.metals/mcp.json` so per-client
   * config files (Cursor, Claude Desktop, …) don't collide.
   * Default `"sigil"` — Metals supports an opaque string here.
   */
  protected def mcpClientName: String = "sigil"

  override def workspaceFolders(): CompletableFuture[java.util.List[WorkspaceFolder]] =
    CompletableFuture.completedFuture(java.util.Collections.emptyList())

  override def registerCapability(params: RegistrationParams): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def unregisterCapability(params: UnregistrationParams): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def showDocument(params: ShowDocumentParams): CompletableFuture[ShowDocumentResult] =
    CompletableFuture.completedFuture(new ShowDocumentResult(false))

  override def logTrace(params: LogTraceParams): Unit = ()

  override def refreshSemanticTokens(): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshCodeLenses(): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshInlayHints(): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshInlineValues(): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshDiagnostics(): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshFoldingRanges(): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshTextDocumentContent(params: TextDocumentContentRefreshParams): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture[ApplyWorkspaceEditResponse] =
    CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false))

  override def createProgress(params: WorkDoneProgressCreateParams): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def notifyProgress(params: ProgressParams): Unit = {
    val notification = params.getValue
    if (notification != null && notification.isLeft) {
      val wd = notification.getLeft
      val msg = wd match {
        case b: WorkDoneProgressBegin => Option(b.getTitle).orElse(Option(b.getMessage))
        case r: WorkDoneProgressReport => Option(r.getMessage)
        case _: WorkDoneProgressEnd => None
        case _ => None
      }
      msg.foreach(m => publishLine(s"[metals] $m"))
    }
  }

  private def publishLine(line: String): Unit =
    onLogLine.get().foreach(cb => cb(line).handleError(_ => Task.unit).startUnit())

  /**
   * Metals' progress-pulse notification (sigil bug #98). Routes the
   * `text` field through `onStatus` so the in-flight tool's chip
   * (typically `start_metals`) can surface what Metals is actually
   * doing — "indexing scala/java sources", "compiling 47 files",
   * etc. Without this handler lsp4j's GenericEndpoint logs an
   * "Unsupported notification method: metals/status" warning and
   * the progress pulse is dropped.
   */
  @JsonNotification("metals/status")
  def metalsStatus(params: Object): Unit = {
    val text = params match {
      case obj: JsonObject =>
        Option(obj.get("text")).flatMap(t => Option(t.getAsString)).getOrElse("")
      case _ => ""
    }
    if (text.nonEmpty) onStatus.get().foreach(cb => cb(text).handleError(_ => Task.unit).startUnit())
  }
}

object MetalsLanguageClient {

  /**
   * Action titles we'll pick when Metals prompts. Initialisation
   * actions only — the build-import path has to run for indexing
   * to complete. Excludes "More information" (opens doctor in
   * browser), "Start" (HTTP server prompt), "Goto location"
   * (anything pointing at a URL), and similar UI-side actions
   * that have no value for an automated agent.
   */
  val SafeAutoResponseTitles: Set[String] = Set(
    "Import build",
    "Import changes",
    "Don't show again"
  )
}
