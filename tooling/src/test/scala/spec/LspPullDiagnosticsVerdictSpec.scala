package spec

import org.eclipse.lsp4j.{
  Diagnostic, DiagnosticRegistrationOptions, DiagnosticSeverity,
  DocumentDiagnosticParams, DocumentDiagnosticReport, InitializeParams, InitializeResult,
  Position, PublishDiagnosticsParams, Range, RelatedFullDocumentDiagnosticReport,
  ServerCapabilities
}
import org.eclipse.lsp4j.services.{LanguageServer, TextDocumentService, WorkspaceService}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tooling.{LspRecordingClient, LspServerConfig, LspSession, PermissiveWorkspaceEditApplier}

import java.util.concurrent.CompletableFuture

/**
 * The pull-diagnostics VERDICT contract: `textDocument/diagnostic` is
 * a request the server must answer, so — unlike the push model, where
 * a clean overlay open typically produces no publish at all —
 * cleanliness gets an explicit ack. A per-file validator built on the
 * push-generation freshness API burns its full timeout on every clean
 * file and still gets no verdict (observed: 246 of 248 files
 * UNVALIDATED); `pullDiagnosticsVerdict` gives it `Some(Nil)` in one
 * round-trip.
 *
 * Verifies:
 *   1. Capability advertised + clean file → `Some(Nil)` — an actual
 *      verdict, not a timeout.
 *   2. Capability advertised + broken file → `Some(errors)`.
 *   3. No capability → `supportsPullDiagnostics = false` and verdict
 *      `None` without ever calling the server (LSP 3.17 forbids the
 *      request); the push path is the caller's fallback.
 *   4. Request failure → `None`, never `Some(Nil)`.
 */
class LspPullDiagnosticsVerdictSpec extends AnyWordSpec with Matchers {

  /** Minimal scripted LanguageServer whose `textDocument/diagnostic`
    * answers from `respond`. */
  private final class StubServer(respond: DocumentDiagnosticParams => CompletableFuture[DocumentDiagnosticReport])
    extends LanguageServer {
    var diagnosticCalls: Int = 0
    private val tds = new TextDocumentService {
      override def didOpen(params: org.eclipse.lsp4j.DidOpenTextDocumentParams): Unit = ()
      override def didChange(params: org.eclipse.lsp4j.DidChangeTextDocumentParams): Unit = ()
      override def didClose(params: org.eclipse.lsp4j.DidCloseTextDocumentParams): Unit = ()
      override def didSave(params: org.eclipse.lsp4j.DidSaveTextDocumentParams): Unit = ()
      override def diagnostic(params: DocumentDiagnosticParams): CompletableFuture[DocumentDiagnosticReport] = {
        diagnosticCalls += 1
        respond(params)
      }
    }
    private val ws = new WorkspaceService {
      override def didChangeConfiguration(params: org.eclipse.lsp4j.DidChangeConfigurationParams): Unit = ()
      override def didChangeWatchedFiles(params: org.eclipse.lsp4j.DidChangeWatchedFilesParams): Unit = ()
    }
    override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] =
      CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()))
    override def shutdown(): CompletableFuture[Object] = CompletableFuture.completedFuture(null)
    override def exit(): Unit = ()
    override def getTextDocumentService: TextDocumentService = tds
    override def getWorkspaceService: WorkspaceService = ws
  }

  private def session(caps: ServerCapabilities, server: LanguageServer): (LspSession, LspRecordingClient) = {
    val client = new LspRecordingClient(PermissiveWorkspaceEditApplier)
    val s = new LspSession(
      config             = LspServerConfig(languageId = "scala", command = "fake", args = Nil),
      projectRoot        = "/tmp/fake-project",
      process            = null.asInstanceOf[Process],
      server             = server,
      client             = client,
      serverCapabilities = caps
    )
    (s, client)
  }

  private def pullCaps: ServerCapabilities = {
    val caps = new ServerCapabilities()
    caps.setDiagnosticProvider(new DiagnosticRegistrationOptions())
    caps
  }

  private def error(line: Int, message: String): Diagnostic =
    new Diagnostic(new Range(new Position(line, 0), new Position(line, 4)), message,
      DiagnosticSeverity.Error, "stub-server")

  private val uri = "file:///tmp/fake-project/Main.scala"

  "pullDiagnosticsVerdict" should {

    "return Some(Nil) for a clean file — a verdict, not a timeout" in {
      val server = new StubServer(_ => CompletableFuture.completedFuture(
        new DocumentDiagnosticReport(new RelatedFullDocumentDiagnosticReport(java.util.List.of()))))
      val (s, _) = session(pullCaps, server)
      s.pullDiagnosticsVerdict(uri).sync() shouldBe Some(Nil)
      server.diagnosticCalls shouldBe 1
    }

    "return Some(errors) for a broken file" in {
      val d = error(3, "not found: value reason")
      val server = new StubServer(_ => CompletableFuture.completedFuture(
        new DocumentDiagnosticReport(new RelatedFullDocumentDiagnosticReport(java.util.List.of(d)))))
      val (s, _) = session(pullCaps, server)
      val verdict = s.pullDiagnosticsVerdict(uri).sync()
      verdict.map(_.map(_.getMessage.getLeft)) shouldBe Some(List("not found: value reason"))
    }

    "report pull unavailable and never call a server without the capability" in {
      val server = new StubServer(_ => CompletableFuture.completedFuture(
        new DocumentDiagnosticReport(new RelatedFullDocumentDiagnosticReport(java.util.List.of()))))
      val (s, client) = session(new ServerCapabilities(), server)
      s.supportsPullDiagnostics shouldBe false
      s.pullDiagnosticsVerdict(uri).sync() shouldBe None
      server.diagnosticCalls shouldBe 0
      // The push path stays available as the fallback surface.
      client.publishDiagnostics(new PublishDiagnosticsParams(uri, java.util.List.of(error(1, "pushed"))))
      s.diagnosticsFor(uri).map(_.getMessage.getLeft) shouldBe List("pushed")
    }

    "return None on request failure — never Some(Nil)" in {
      val server = new StubServer(_ =>
        CompletableFuture.failedFuture[DocumentDiagnosticReport](new RuntimeException("connection reset")))
      val (s, _) = session(pullCaps, server)
      s.pullDiagnosticsVerdict(uri).sync() shouldBe None
    }
  }
}
