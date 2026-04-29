package sigil.tooling

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.{LanguageClient, LanguageServer}
import rapid.Task

import java.io.File
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, Executors}
import scala.jdk.CollectionConverters.*

/**
 * Long-lived LSP client session — wraps one language-server
 * subprocess pinned to a specific project root. Spawned lazily by
 * [[LspManager]] on first request and kept warm until the idle
 * timeout fires.
 *
 * Methods on this class are framework-internal; agent-facing tools
 * call into them through [[LspManager.withSession]].
 *
 * Diagnostics arriving via `textDocument/publishDiagnostics`
 * notifications are accumulated in a per-URI `AtomicReference` so
 * tools can read the latest snapshot without needing to subscribe to
 * the wire stream. The contract is "snapshot of last-published
 * diagnostics", which matches how editors surface them too.
 */
final class LspSession(val config: LspServerConfig,
                       val projectRoot: String,
                       process: Process,
                       server: LanguageServer,
                       client: LspSession.RecordingClient) {

  /** Bumps on every method call so [[LspManager]] can drop idle sessions. */
  private val lastUseAt: AtomicLong = new AtomicLong(System.currentTimeMillis())

  def touch(): Unit = lastUseAt.set(System.currentTimeMillis())

  def idleSince: Long = lastUseAt.get()

  /** Latest published diagnostics for the given URI, or empty if none
    * have been received. The session refreshes this on every
    * `textDocument/publishDiagnostics` notification. */
  def diagnosticsFor(uri: String): List[Diagnostic] =
    client.diagnostics.getOrDefault(uri, java.util.Collections.emptyList()).asScala.toList

  def allDiagnostics: Map[String, List[Diagnostic]] =
    client.diagnostics.asScala.view.mapValues(_.asScala.toList).toMap

  /** Notify the server we're about to read/edit a document. Required
    * before goto-definition / hover / etc. work — the LSP protocol
    * routes those queries through the open-documents map. */
  def didOpen(uri: String, languageId: String, text: String): Task[Unit] = Task {
    touch()
    val item = new TextDocumentItem(uri, languageId, 1, text)
    server.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(item))
  }

  def didClose(uri: String): Task[Unit] = Task {
    touch()
    val id = new TextDocumentIdentifier(uri)
    server.getTextDocumentService.didClose(new DidCloseTextDocumentParams(id))
  }

  def gotoDefinition(uri: String, line: Int, character: Int): Task[List[Location]] = Task.defer {
    touch()
    val params = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.definition(params)).map { either =>
      if (either == null) Nil
      else if (either.isLeft) either.getLeft.asScala.toList
      else either.getRight.asScala.toList.map { ll =>
        val l = new Location()
        l.setUri(ll.getTargetUri)
        l.setRange(ll.getTargetRange)
        l
      }
    }
  }

  def hover(uri: String, line: Int, character: Int): Task[Option[Hover]] = Task.defer {
    touch()
    val params = new HoverParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.hover(params)).map(Option(_))
  }

  /** Force a wait-for-diagnostics window. The server publishes
    * diagnostics asynchronously after `didOpen` / `didChange`; tools
    * that want a fresh snapshot call `didOpen` then sleep for the
    * window length before reading. Caller-supplied so latency-sensitive
    * tools can pick a shorter wait. */
  def waitForDiagnostics(windowMs: Long): Task[Unit] =
    Task.sleep(scala.concurrent.duration.FiniteDuration(windowMs, "millis"))

  def shutdown(): Task[Unit] = Task {
    try { server.shutdown().get(2, java.util.concurrent.TimeUnit.SECONDS); () } catch { case _: Throwable => () }
    try { server.exit() } catch { case _: Throwable => () }
    try { process.destroy() } catch { case _: Throwable => () }
    if (process.isAlive) {
      process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      if (process.isAlive) process.destroyForcibly()
    }
    ()
  }
}

object LspSession {

  /** Spawn a language-server subprocess, perform the LSP handshake,
    * and return a ready-to-use session. Failures during spawn or
    * `initialize` propagate as `Task.error`. */
  def spawn(config: LspServerConfig, projectRoot: String): Task[LspSession] = Task.defer {
    val pb = new ProcessBuilder((config.command :: config.args).asJava)
    pb.directory(new File(projectRoot))
    pb.redirectErrorStream(false)
    config.env.foreach { case (k, v) => pb.environment().put(k, v) }
    val process = pb.start()

    val client = new RecordingClient
    val executor = Executors.newSingleThreadExecutor { r =>
      val t = new Thread(r, s"lsp-${config.languageId}")
      t.setDaemon(true)
      t
    }
    val launcher = new Launcher.Builder[LanguageServer]()
      .setLocalService(client)
      .setRemoteInterface(classOf[LanguageServer])
      .setInput(process.getInputStream)
      .setOutput(process.getOutputStream)
      .setExecutorService(executor)
      .create()
    val server = launcher.getRemoteProxy
    client.connect(server)
    launcher.startListening()

    val initParams = new InitializeParams()
    initParams.setProcessId(ProcessHandle.current().pid().toInt)
    val workspaceFolder = new WorkspaceFolder(new File(projectRoot).toURI.toString, new File(projectRoot).getName)
    initParams.setWorkspaceFolders(java.util.Collections.singletonList(workspaceFolder))
    initParams.setCapabilities(new ClientCapabilities())

    fromFuture(server.initialize(initParams)).map { _ =>
      server.initialized(new InitializedParams())
      new LspSession(config, projectRoot, process, server, client)
    }
  }

  /** Bridge an lsp4j `CompletableFuture[T]` to a rapid `Task[T]`.
    * Failures are unwrapped from `CompletionException` so callers
    * see the underlying cause. */
  def fromFuture[T](future: CompletableFuture[T]): Task[T] = {
    val completable = Task.completable[T]
    future.whenComplete { (value, error) =>
      if (error != null) {
        val unwrapped = error match {
          case ce: java.util.concurrent.CompletionException if ce.getCause != null => ce.getCause
          case other                                                               => other
        }
        completable.failure(unwrapped)
      } else completable.success(value)
    }
    completable
  }

  /** Minimal `LanguageClient` impl that just records diagnostics. The
    * other notifications (`logMessage`, `showMessage`, `telemetry`)
    * are no-ops — agents don't need a chat surface for those. */
  final class RecordingClient extends LanguageClient {
    val diagnostics: ConcurrentHashMap[String, java.util.List[Diagnostic]] = new ConcurrentHashMap()
    private val serverRef: AtomicReference[LanguageServer] = new AtomicReference()

    def connect(s: LanguageServer): Unit = serverRef.set(s)

    override def publishDiagnostics(params: PublishDiagnosticsParams): Unit =
      diagnostics.put(params.getUri, params.getDiagnostics)

    override def telemetryEvent(params: Object): Unit = ()

    override def showMessage(params: MessageParams): Unit = ()

    override def showMessageRequest(params: ShowMessageRequestParams): CompletableFuture[MessageActionItem] =
      CompletableFuture.completedFuture(null)

    override def logMessage(params: MessageParams): Unit = ()
  }
}
