package sigil.tooling

import com.google.gson.JsonObject
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.{LanguageClient, LanguageServer}

import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicReference

/**
 * `LanguageClient` impl that captures the things agents care about:
 *   - `publishDiagnostics` → per-URI snapshot map
 *   - `applyEdit` → delegates to the [[WorkspaceEditApplier]] so
 *     server-suggested edits (rename, code-action) actually land
 *     on disk via `FileSystemContext`
 *   - `workDoneProgress/create` and `progress` → lifecycle map so
 *     `LspSession.waitForIdle` can block until indexing finishes
 *
 * Other server-side notifications (`logMessage`, `showMessage`,
 * `telemetry`) are no-ops — agents don't have a chat surface for
 * them. Apps that want to capture log output subclass.
 */
final class LspRecordingClient(applier: WorkspaceEditApplier) extends LanguageClient {
  val diagnostics: ConcurrentHashMap[String, java.util.List[Diagnostic]] = new ConcurrentHashMap()
  val progressTokens: ConcurrentHashMap[String, java.lang.Boolean] = new ConcurrentHashMap()
  private val serverRef: AtomicReference[LanguageServer] = new AtomicReference()

  /** Per-call status callback. The active LSP tool (via
    * [[LspToolSupport.withSessionTyped]]) registers a Some(handler)
    * that publishes [[sigil.event.ToolProgress]] for the chip;
    * cleared back to None on exit. Routes Metals' `metals/status`
    * notification (and analogous server-extension status pulses) so
    * indexing / build-import progress flows into the agent's UI
    * instead of being dropped by lsp4j's GenericEndpoint. Sigil bug
    * #98. */
  private val statusCallback: AtomicReference[Option[String => Unit]] =
    new AtomicReference(None)

  /** Install (or clear with `None`) the status-update callback for
    * the currently-running tool. Thread-safe; replacement is
    * atomic. */
  def setStatusCallback(cb: Option[String => Unit]): Unit =
    statusCallback.set(cb)

  def connect(s: LanguageServer): Unit = serverRef.set(s)

  override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
    diagnostics.put(params.getUri, params.getDiagnostics)

  override def telemetryEvent(params: Object): Unit = ()
  override def showMessage(params: MessageParams): Unit = ()

  /** Auto-pick known-safe initialisation actions so prompted servers
    * (Metals detecting an sbt project, JDTLS asking about which
    * JDK, …) can complete their setup without a human. Returns
    * `null` (= dismiss) for everything else; blindly picking the
    * first action opens browsers (Metals' "More information" →
    * Doctor URL) and starts ancillary HTTP servers, which have no
    * value for an automated agent.
    *
    * Apps that want a different policy subclass and override. */
  override def showMessageRequest(params: ShowMessageRequestParams): CompletableFuture[MessageActionItem] = {
    import scala.jdk.CollectionConverters.*
    val actions = Option(params.getActions).map(_.asScala.toList.map(_.getTitle)).getOrElse(Nil)
    val item = actions
      .find(LspRecordingClient.SafeAutoResponseTitles.contains)
      .map(t => new MessageActionItem(t))
      .orNull
    CompletableFuture.completedFuture(item)
  }

  override def logMessage(params: MessageParams): Unit = ()

  // Explicit overrides for every default method declared on
  // [[LanguageClient]]. Sigil bug #93 — without these, lsp4j's
  // `AnnotationUtil.findRpcMethods` reflection scan throws
  // "Duplicate RPC method workspace/configuration" (and the
  // analogous error for the other defaults) when our Scala 3
  // subclass is registered as a local service. The Scala compiler
  // emits synthetic forwarders alongside the inherited defaults
  // and the scan double-counts. Same fix as
  // [[sigil.metals.MetalsLanguageClient]].
  override def configuration(params: ConfigurationParams): CompletableFuture[java.util.List[Object]] =
    CompletableFuture.completedFuture(java.util.Collections.emptyList[Object]())

  override def workspaceFolders(): CompletableFuture[java.util.List[WorkspaceFolder]] =
    CompletableFuture.completedFuture(java.util.Collections.emptyList())

  override def registerCapability(params: RegistrationParams): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def unregisterCapability(params: UnregistrationParams): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def showDocument(params: ShowDocumentParams): CompletableFuture[ShowDocumentResult] =
    CompletableFuture.completedFuture(new ShowDocumentResult(false))

  /** Metals' progress-pulse notification (sigil bug #98). Metals
    * pushes status updates ("indexing scala/java sources",
    * "compiling 47 files", "Importing build via sbt-bloop") via this
    * extension method. lsp4j's GenericEndpoint logs an "Unsupported
    * notification method" warning when a server method has no
    * @JsonNotification handler — declaring it here routes the text
    * through the per-call status callback installed by
    * [[LspToolSupport.withSessionTyped]] so the running tool's chip
    * surfaces what Metals is doing instead of looking frozen.
    *
    * The wire shape is `{ text, show?, hide?, tooltip?, command? }`;
    * we only consume `text`. Other server-extension protocols (e.g.
    * a future `ts/status` for ts-server) can route through the same
    * statusCallback by adding their own @JsonNotification handler. */
  @JsonNotification("metals/status")
  def metalsStatus(params: Object): Unit = {
    val text = params match {
      case obj: JsonObject =>
        Option(obj.get("text")).flatMap(t => Option(t.getAsString)).getOrElse("")
      case _ => ""
    }
    if (text.nonEmpty) statusCallback.get().foreach(_.apply(text))
  }

  override def logTrace(params: LogTraceParams): Unit = ()

  override def refreshSemanticTokens(): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshCodeLenses():    CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshInlayHints():    CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshInlineValues():  CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshDiagnostics():   CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshFoldingRanges(): CompletableFuture[Void] = CompletableFuture.completedFuture(null)
  override def refreshTextDocumentContent(params: TextDocumentContentRefreshParams): CompletableFuture[Void] =
    CompletableFuture.completedFuture(null)

  override def applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture[ApplyWorkspaceEditResponse] = {
    val f = new CompletableFuture[ApplyWorkspaceEditResponse]()
    try {
      val applied = applier.apply(params.getEdit)
      f.complete(new ApplyWorkspaceEditResponse(applied))
    } catch {
      case t: Throwable =>
        val resp = new ApplyWorkspaceEditResponse(false)
        resp.setFailureReason(t.getMessage)
        f.complete(resp)
    }
    f
  }

  override def createProgress(params: WorkDoneProgressCreateParams): CompletableFuture[Void] = {
    val token = tokenString(params.getToken)
    progressTokens.put(token, java.lang.Boolean.TRUE)
    CompletableFuture.completedFuture(null)
  }

  override def notifyProgress(params: ProgressParams): Unit = {
    val token = tokenString(params.getToken)
    val notification = params.getValue
    if (notification != null && notification.isLeft) {
      val wd = notification.getLeft
      if (wd.isInstanceOf[WorkDoneProgressEnd]) {
        progressTokens.remove(token)
      }
    }
  }

  private def tokenString(token: LspEither[String, Integer]): String =
    if (token == null) ""
    else if (token.isLeft) token.getLeft
    else token.getRight.toString
}

object LspRecordingClient {
  /** Action titles auto-picked from showMessageRequest prompts.
    * Only initialisation actions — picking "More information" or
    * "Start" (HTTP server prompts) opens browsers and spawns
    * ancillary services unrelated to the agent's task. */
  val SafeAutoResponseTitles: Set[String] = Set(
    "Import build",
    "Import changes",
    "Don't show again"
  )
}
