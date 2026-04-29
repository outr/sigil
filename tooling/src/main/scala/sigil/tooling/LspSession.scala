package sigil.tooling

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
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
 * Two pieces of mutable state live alongside the wire layer:
 *   - per-URI diagnostic snapshots, refreshed on every server
 *     `textDocument/publishDiagnostics` notification
 *   - per-URI document version, bumped on every `didChange` so the
 *     server can reconcile incremental edits
 */
final class LspSession(val config: LspServerConfig,
                       val projectRoot: String,
                       process: Process,
                       server: LanguageServer,
                       client: LspRecordingClient) {

  private val lastUseAt: AtomicLong = new AtomicLong(System.currentTimeMillis())
  private val versions: ConcurrentHashMap[String, AtomicLong] = new ConcurrentHashMap()

  /** The most recent code-action set returned to a tool, keyed by URI.
    * `lsp_apply_code_action` looks up by index here so agents can pick
    * an action without serializing the action object across the tool
    * boundary. Replaced wholesale on each `codeAction` call. */
  private val lastCodeActions: ConcurrentHashMap[String, List[LspEither[Command, CodeAction]]] = new ConcurrentHashMap()

  def cachedCodeActions(uri: String): List[LspEither[Command, CodeAction]] =
    Option(lastCodeActions.get(uri)).getOrElse(Nil)

  def touch(): Unit = lastUseAt.set(System.currentTimeMillis())
  def idleSince: Long = lastUseAt.get()

  /** Get the next document version for a URI; first access seeds at 1. */
  private def nextVersion(uri: String): Int =
    versions.computeIfAbsent(uri, _ => new AtomicLong(0L)).incrementAndGet().toInt

  // ---- diagnostics (push-model snapshot) ----

  def diagnosticsFor(uri: String): List[Diagnostic] =
    client.diagnostics.getOrDefault(uri, java.util.Collections.emptyList()).asScala.toList

  def allDiagnostics: Map[String, List[Diagnostic]] =
    client.diagnostics.asScala.view.mapValues(_.asScala.toList).toMap

  /** WorkDoneProgress tokens still in-flight. Agents wait on this
    * before issuing index-dependent queries — `Sage` picks up "Metals
    * is indexing your build" by checking this set. */
  def inFlightProgress: Set[String] = client.progressTokens.keySet().asScala.toSet

  /** Block until no progress tokens remain or until the deadline.
    * Implemented as polling — the lsp4j `WorkDoneProgress` end
    * notification doesn't expose a future per token, and most servers
    * settle within a few hundred ms anyway. */
  def waitForIdle(timeoutMs: Long, pollMs: Long = 100L): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop: Task[Unit] = Task.defer {
      if (client.progressTokens.isEmpty || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(scala.concurrent.duration.FiniteDuration(pollMs, "millis")).flatMap(_ => loop)
    }
    loop
  }

  def waitForDiagnostics(windowMs: Long): Task[Unit] =
    Task.sleep(scala.concurrent.duration.FiniteDuration(windowMs, "millis"))

  // ---- document lifecycle ----

  /** Open a document with the server. Idempotent in practice — most
    * servers tolerate a re-open as a `didChange`-equivalent. */
  def didOpen(uri: String, languageId: String, text: String): Task[Unit] = Task {
    touch()
    val v = nextVersion(uri)
    val item = new TextDocumentItem(uri, languageId, v, text)
    server.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(item))
  }

  /** Notify the server that a document's full text has changed.
    * The simple "send full content each time" path; works with
    * every server's default `textDocumentSync.change = Full` and
    * is the safest contract for an agent that just rewrote a file. */
  def didChangeFull(uri: String, text: String): Task[Unit] = Task {
    touch()
    val v = nextVersion(uri)
    val id = new VersionedTextDocumentIdentifier(uri, v)
    val change = new TextDocumentContentChangeEvent(text)
    server.getTextDocumentService.didChange(
      new DidChangeTextDocumentParams(id, java.util.Collections.singletonList(change))
    )
  }

  def didSave(uri: String, text: Option[String] = None): Task[Unit] = Task {
    touch()
    val params = new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri))
    text.foreach(params.setText)
    server.getTextDocumentService.didSave(params)
  }

  def didClose(uri: String): Task[Unit] = Task {
    touch()
    val id = new TextDocumentIdentifier(uri)
    server.getTextDocumentService.didClose(new DidCloseTextDocumentParams(id))
  }

  /** Forwarded to the server as `workspace/didChangeWatchedFiles`.
    * Apps wire this from their `EditFileTool` (etc.) so the server's
    * index stays current after framework-side writes. */
  def didChangeWatchedFiles(events: List[FileEvent]): Task[Unit] = Task {
    touch()
    server.getWorkspaceService.didChangeWatchedFiles(new DidChangeWatchedFilesParams(events.asJava))
  }

  // ---- navigation ----

  def gotoDefinition(uri: String, line: Int, character: Int): Task[List[Location]] = Task.defer {
    touch()
    val params = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.definition(params)).map(LspSession.flattenLocations)
  }

  def typeDefinition(uri: String, line: Int, character: Int): Task[List[Location]] = Task.defer {
    touch()
    val params = new TypeDefinitionParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.typeDefinition(params)).map(LspSession.flattenLocations)
  }

  def implementation(uri: String, line: Int, character: Int): Task[List[Location]] = Task.defer {
    touch()
    val params = new ImplementationParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.implementation(params)).map(LspSession.flattenLocations)
  }

  def references(uri: String, line: Int, character: Int, includeDeclaration: Boolean = true): Task[List[Location]] = Task.defer {
    touch()
    val ctx = new ReferenceContext(includeDeclaration)
    val params = new ReferenceParams(new TextDocumentIdentifier(uri), new Position(line, character), ctx)
    LspSession.fromFuture(server.getTextDocumentService.references(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  def documentSymbols(uri: String): Task[List[LspEither[SymbolInformation, DocumentSymbol]]] = Task.defer {
    touch()
    val params = new DocumentSymbolParams(new TextDocumentIdentifier(uri))
    LspSession.fromFuture(server.getTextDocumentService.documentSymbol(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  def workspaceSymbols(query: String): Task[List[SymbolHit]] = Task.defer {
    touch()
    val params = new WorkspaceSymbolParams(query)
    LspSession.fromFuture(server.getWorkspaceService.symbol(params)).map { either =>
      if (either == null) Nil
      else if (either.isLeft) either.getLeft.asScala.toList.map(SymbolHit.fromSymbolInformation)
      else either.getRight.asScala.toList.map(SymbolHit.fromWorkspaceSymbol)
    }
  }

  def hover(uri: String, line: Int, character: Int): Task[Option[Hover]] = Task.defer {
    touch()
    val params = new HoverParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.hover(params)).map(Option(_))
  }

  def signatureHelp(uri: String, line: Int, character: Int): Task[Option[SignatureHelp]] = Task.defer {
    touch()
    val params = new SignatureHelpParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.signatureHelp(params)).map(Option(_))
  }

  def completion(uri: String, line: Int, character: Int): Task[List[CompletionItem]] = Task.defer {
    touch()
    val params = new CompletionParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.completion(params)).map { either =>
      if (either == null) Nil
      else if (either.isLeft) either.getLeft.asScala.toList
      else Option(either.getRight.getItems).map(_.asScala.toList).getOrElse(Nil)
    }
  }

  def resolveCompletionItem(item: CompletionItem): Task[CompletionItem] = Task.defer {
    touch()
    LspSession.fromFuture(server.getTextDocumentService.resolveCompletionItem(item))
  }

  def codeAction(uri: String,
                 range: Range,
                 onlyKinds: List[String] = Nil): Task[List[LspEither[Command, CodeAction]]] = Task.defer {
    touch()
    val ctx = new CodeActionContext(diagnosticsFor(uri).asJava)
    if (onlyKinds.nonEmpty) ctx.setOnly(onlyKinds.asJava)
    val params = new CodeActionParams(new TextDocumentIdentifier(uri), range, ctx)
    LspSession.fromFuture(server.getTextDocumentService.codeAction(params)).map { result =>
      val list = if (result == null) Nil else result.asScala.toList
      lastCodeActions.put(uri, list)
      list
    }
  }

  def resolveCodeAction(action: CodeAction): Task[CodeAction] = Task.defer {
    touch()
    LspSession.fromFuture(server.getTextDocumentService.resolveCodeAction(action))
  }

  def executeCommand(command: String, arguments: List[Object] = Nil): Task[Object] = Task.defer {
    touch()
    val params = new ExecuteCommandParams(command, arguments.asJava)
    LspSession.fromFuture(server.getWorkspaceService.executeCommand(params))
  }

  def formatting(uri: String, options: FormattingOptions): Task[List[TextEdit]] = Task.defer {
    touch()
    val params = new DocumentFormattingParams(new TextDocumentIdentifier(uri), options)
    LspSession.fromFuture(server.getTextDocumentService.formatting(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  def rangeFormatting(uri: String, range: Range, options: FormattingOptions): Task[List[TextEdit]] = Task.defer {
    touch()
    val params = new DocumentRangeFormattingParams(new TextDocumentIdentifier(uri), options, range)
    LspSession.fromFuture(server.getTextDocumentService.rangeFormatting(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  def rename(uri: String, line: Int, character: Int, newName: String): Task[Option[WorkspaceEdit]] = Task.defer {
    touch()
    val params = new RenameParams(new TextDocumentIdentifier(uri), new Position(line, character), newName)
    LspSession.fromFuture(server.getTextDocumentService.rename(params)).map(Option(_))
  }

  def prepareRename(uri: String, line: Int, character: Int): Task[Option[Range]] = Task.defer {
    touch()
    val params = new PrepareRenameParams(new TextDocumentIdentifier(uri), new Position(line, character))
    LspSession.fromFuture(server.getTextDocumentService.prepareRename(params)).map { either =>
      Option(either).flatMap { e =>
        // PrepareRenameResult shapes: Range | { range, placeholder } | { defaultBehavior }
        if (e.isFirst) Some(e.getFirst)
        else if (e.isSecond) Option(e.getSecond.getRange)
        else None
      }
    }
  }

  def foldingRange(uri: String): Task[List[FoldingRange]] = Task.defer {
    touch()
    val params = new FoldingRangeRequestParams(new TextDocumentIdentifier(uri))
    LspSession.fromFuture(server.getTextDocumentService.foldingRange(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  def selectionRange(uri: String, positions: List[Position]): Task[List[SelectionRange]] = Task.defer {
    touch()
    val params = new SelectionRangeParams(new TextDocumentIdentifier(uri), positions.asJava)
    LspSession.fromFuture(server.getTextDocumentService.selectionRange(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  def pullDiagnostics(uri: String): Task[Option[DocumentDiagnosticReport]] = Task.defer {
    touch()
    val params = new DocumentDiagnosticParams(new TextDocumentIdentifier(uri))
    LspSession.fromFuture(server.getTextDocumentService.diagnostic(params)).map(Option(_))
  }

  def inlayHints(uri: String, range: Range): Task[List[InlayHint]] = Task.defer {
    touch()
    val params = new InlayHintParams(new TextDocumentIdentifier(uri), range)
    LspSession.fromFuture(server.getTextDocumentService.inlayHint(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  def codeLens(uri: String): Task[List[CodeLens]] = Task.defer {
    touch()
    val params = new CodeLensParams(new TextDocumentIdentifier(uri))
    LspSession.fromFuture(server.getTextDocumentService.codeLens(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  def documentLinks(uri: String): Task[List[DocumentLink]] = Task.defer {
    touch()
    val params = new DocumentLinkParams(new TextDocumentIdentifier(uri))
    LspSession.fromFuture(server.getTextDocumentService.documentLink(params)).map { result =>
      if (result == null) Nil else result.asScala.toList
    }
  }

  // ---- shutdown ----

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

  def spawn(config: LspServerConfig, projectRoot: String, applier: WorkspaceEditApplier): Task[LspSession] = Task.defer {
    val pb = new ProcessBuilder((config.command :: config.args).asJava)
    pb.directory(new File(projectRoot))
    pb.redirectErrorStream(false)
    config.env.foreach { case (k, v) => pb.environment().put(k, v) }
    val process = pb.start()

    val client = new LspRecordingClient(applier)
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
    initParams.setCapabilities(buildClientCapabilities())

    fromFuture(server.initialize(initParams)).map { _ =>
      server.initialized(new InitializedParams())
      new LspSession(config, projectRoot, process, server, client)
    }
  }

  /** Capability declarations for every feature this module exposes
    * as a tool. Servers gate behaviors on these — Metals only sends
    * inlay hints when the client says it supports them, etc. */
  private def buildClientCapabilities(): ClientCapabilities = {
    val caps = new ClientCapabilities()

    val td = new TextDocumentClientCapabilities()
    td.setDefinition(new DefinitionCapabilities(true))
    td.setTypeDefinition(new TypeDefinitionCapabilities(true))
    td.setImplementation(new ImplementationCapabilities(true))
    td.setReferences(new ReferencesCapabilities(true))
    td.setHover(new HoverCapabilities(true))
    td.setSignatureHelp(new SignatureHelpCapabilities(true))
    td.setCompletion(new CompletionCapabilities(true))
    td.setDocumentSymbol(new DocumentSymbolCapabilities(true))
    td.setRename(new RenameCapabilities(true, true))
    td.setFormatting(new FormattingCapabilities(true))
    td.setRangeFormatting(new RangeFormattingCapabilities(true))
    td.setCodeAction(new CodeActionCapabilities(true))
    td.setCodeLens(new CodeLensCapabilities(true))
    td.setDocumentLink(new DocumentLinkCapabilities(true))
    val folding = new FoldingRangeCapabilities()
    folding.setDynamicRegistration(true)
    td.setFoldingRange(folding)
    td.setSelectionRange(new SelectionRangeCapabilities(true))
    td.setInlayHint(new InlayHintCapabilities(true))
    td.setDiagnostic(new DiagnosticCapabilities(true))
    td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true))
    caps.setTextDocument(td)

    val ws = new WorkspaceClientCapabilities()
    ws.setApplyEdit(true)
    ws.setSymbol(new SymbolCapabilities(true))
    ws.setExecuteCommand(new ExecuteCommandCapabilities(true))
    ws.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities(true))
    val we = new WorkspaceEditCapabilities()
    we.setDocumentChanges(true)
    ws.setWorkspaceEdit(we)
    caps.setWorkspace(ws)

    val window = new WindowClientCapabilities()
    window.setWorkDoneProgress(true)
    caps.setWindow(window)

    caps
  }

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

  /** Coalesce `Either<List[Location], List[LocationLink]>` into a flat
    * `List[Location]`. LSP-3.14 servers can return either shape; the
    * agent only cares about the URI + range, so we collapse early. */
  def flattenLocations(either: LspEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]): List[Location] =
    if (either == null) Nil
    else if (either.isLeft) either.getLeft.asScala.toList.map(identity[Location])
    else either.getRight.asScala.toList.map { ll =>
      val l = new Location()
      l.setUri(ll.getTargetUri)
      l.setRange(ll.getTargetRange)
      l
    }

}
