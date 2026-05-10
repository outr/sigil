package spec

import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity, DiagnosticRegistrationOptions, Position, PublishDiagnosticsParams, Range, ServerCapabilities, WorkspaceEdit}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tooling.{LspRecordingClient, LspServerConfig, LspSession, PermissiveWorkspaceEditApplier}

import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for sigil bug #100 — `lsp_pull_diagnostics` must check
 * the server's advertised `diagnosticProvider` capability before
 * issuing the LSP 3.17 `textDocument/diagnostic` request. Pre-fix
 * the tool called the pull method unconditionally; servers that
 * implement only legacy `publishDiagnostics` push (Metals 1.6.7
 * and many others) returned `MethodNotFound` and the tool failed.
 *
 * Three locked invariants:
 *   1. `LspSession.serverCapabilities` carries whatever the
 *      `initialize` response advertised.
 *   2. The session's push-diagnostics cache populates from
 *      `publishDiagnostics` notifications and is readable via
 *      `diagnosticsFor(uri)` — this is the fallback path the
 *      tool reads when the server doesn't advertise pull.
 *   3. The capability gate logic in [[sigil.tooling.LspPullDiagnosticsTool]]
 *      `Option(serverCapabilities.getDiagnosticProvider).isDefined`
 *      reflects the server's actual support: false for a push-only
 *      server, true once the server advertises support.
 */
class LspPullDiagnosticsCapabilitySpec extends AnyWordSpec with Matchers {

  /** Construct an [[LspSession]] without spinning up a real LSP4J
    * launcher. The session's mutable surface (push-diagnostics
    * cache via `client.publishDiagnostics`, capability check via
    * `serverCapabilities`) is what the tool's pull/push routing
    * reads — those don't need a live server proxy. */
  private def synthSession(caps: ServerCapabilities): (LspSession, LspRecordingClient) = {
    val client = new LspRecordingClient(PermissiveWorkspaceEditApplier)
    val session = new LspSession(
      config              = LspServerConfig(languageId = "scala", command = "fake", args = Nil),
      projectRoot         = "/tmp/fake-project",
      process             = null.asInstanceOf[Process],
      server              = null.asInstanceOf[org.eclipse.lsp4j.services.LanguageServer],
      client              = client,
      serverCapabilities  = caps
    )
    (session, client)
  }

  private def synthDiagnostic(line: Int, col: Int, message: String, severity: DiagnosticSeverity): Diagnostic =
    new Diagnostic(
      new Range(new Position(line, col), new Position(line, col + 4)),
      message,
      severity,
      "fake-server"
    )

  "LspSession.serverCapabilities (#100)" should {

    "be None for diagnosticProvider on a push-only server" in {
      val caps = new ServerCapabilities()
      // Deliberately leave caps.setDiagnosticProvider unset —
      // mirrors what Metals 1.6.7 advertises during `initialize`.
      val (session, _) = synthSession(caps)
      Option(session.serverCapabilities.getDiagnosticProvider) shouldBe None
    }

    "be Some when the server advertises pull-diagnostics support" in {
      val caps = new ServerCapabilities()
      caps.setDiagnosticProvider(new DiagnosticRegistrationOptions())
      val (session, _) = synthSession(caps)
      Option(session.serverCapabilities.getDiagnosticProvider).isDefined shouldBe true
    }
  }

  "LspSession push-diagnostics cache (#100 fallback path)" should {

    "populate from publishDiagnostics notifications and surface via diagnosticsFor" in {
      val (session, client) = synthSession(new ServerCapabilities())
      val uri = "file:///tmp/fake-project/Main.scala"
      val d1 = synthDiagnostic(0, 0, "missing import", DiagnosticSeverity.Error)
      val d2 = synthDiagnostic(2, 8, "unused variable", DiagnosticSeverity.Warning)
      val params = new PublishDiagnosticsParams(uri, java.util.List.of(d1, d2))

      client.publishDiagnostics(params)
      val cached = session.diagnosticsFor(uri)
      cached.size shouldBe 2
      val messages = cached.map(d => d.getMessage.getLeft)
      messages should contain allOf ("missing import", "unused variable")
    }
  }

  "LspPullDiagnosticsTool capability gate logic (#100)" should {

    "route to the push-cache (NOT pull) when server doesn't advertise diagnosticProvider" in {
      val (session, client) = synthSession(new ServerCapabilities())
      val uri = "file:///tmp/fake-project/Main.scala"
      val d  = synthDiagnostic(0, 0, "broken", DiagnosticSeverity.Error)
      client.publishDiagnostics(new PublishDiagnosticsParams(uri, java.util.List.of(d)))

      // The exact gate the tool runs.
      val supportsPull = Option(session.serverCapabilities.getDiagnosticProvider).isDefined
      supportsPull shouldBe false

      // When the gate falls through to push, it reads
      // `session.diagnosticsFor(uri)`. The cache must surface what
      // the server pushed, including a non-empty list.
      val pushed = session.diagnosticsFor(uri)
      pushed should not be empty
      pushed.map(d => d.getMessage.getLeft) should contain ("broken")
    }

    "report the gate as `true` when the server advertises support — tool would route to pull" in {
      val caps = new ServerCapabilities()
      caps.setDiagnosticProvider(new DiagnosticRegistrationOptions())
      val (session, _) = synthSession(caps)

      val supportsPull = Option(session.serverCapabilities.getDiagnosticProvider).isDefined
      supportsPull shouldBe true
    }
  }
}
