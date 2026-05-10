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

  /** `window/showMessage` — server-pushed user-visible status (e.g.
    * "Importing build…"). Routes the text through the per-call
    * status callback so the active tool's chip surfaces the
    * message; no-op when nothing's listening. */
  override def showMessage(params: MessageParams): Unit =
    routeStatus(Option(params).flatMap(p => Option(p.getMessage)).getOrElse(""))

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

  /** `window/logMessage` — server log output. Routed through the
    * status callback so log lines from the LSP server surface in
    * the running tool's chip (e.g. Metals' "Indexing scala
    * sources" / "Compiling N files"). */
  override def logMessage(params: MessageParams): Unit =
    routeStatus(Option(params).flatMap(p => Option(p.getMessage)).getOrElse(""))

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
    routeStatus(text)
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

  /** `$/progress` — LSP 3.15+ structured progress. WorkDoneProgress
    * `Begin` carries the operation title + optional initial message;
    * `Report` carries an updated message and optional percentage;
    * `End` clears the token. All three route their text (with the
    * percentage appended when present) through the per-call status
    * callback so the running tool's chip surfaces server-side
    * progress like "Indexing scala sources (38%)". */
  override def notifyProgress(params: ProgressParams): Unit = {
    val token = tokenString(params.getToken)
    val notification = params.getValue
    if (notification != null && notification.isLeft) {
      val wd = notification.getLeft
      wd match {
        case begin: WorkDoneProgressBegin =>
          val title = Option(begin.getTitle).getOrElse("")
          val msg   = Option(begin.getMessage).filter(_.nonEmpty)
          routeStatus(formatProgress(title, msg, Option(begin.getPercentage).map(_.intValue)))
        case report: WorkDoneProgressReport =>
          val msg = Option(report.getMessage).getOrElse("")
          routeStatus(formatProgress(msg, None, Option(report.getPercentage).map(_.intValue)))
        case end: WorkDoneProgressEnd =>
          val msg = Option(end.getMessage).getOrElse("")
          if (msg.nonEmpty) routeStatus(msg)
          progressTokens.remove(token)
        case _ => ()
      }
    }
  }

  private def formatProgress(primary: String, secondary: Option[String], percentage: Option[Int]): String = {
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    if (primary.nonEmpty) parts += primary
    secondary.filter(_.nonEmpty).foreach(parts += _)
    val text = parts.mkString(" — ")
    percentage match {
      case Some(p) if p >= 0 && text.nonEmpty => s"$text ($p%)"
      case Some(p) if p >= 0                  => s"$p%"
      case _                                  => text
    }
  }

  private def routeStatus(text: String): Unit = {
    val trimmed = Option(text).getOrElse("").trim
    if (trimmed.nonEmpty) statusCallback.get().foreach(_.apply(trimmed))
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
