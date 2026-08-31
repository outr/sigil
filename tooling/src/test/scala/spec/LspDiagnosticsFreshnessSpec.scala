package spec

import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity, Position, PublishDiagnosticsParams, Range, ServerCapabilities}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.tooling.{LspRecordingClient, LspServerConfig, LspSession, PermissiveWorkspaceEditApplier}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Diagnostics freshness. The blind-sleep `waitForDiagnostics(windowMs)`
 * plus a last-write-wins snapshot map could not distinguish "clean"
 * from "no answer yet" from "stale for a previous document version" —
 * a validator sweeping files against a still-indexing server silently
 * false-passed files that carried dozens of compile errors. The
 * publish-generation API must:
 *
 *   1. not report fresh until a publish NEWER than the captured
 *      generation arrives (a stale v1 publish in the map doesn't
 *      satisfy a v2 wait);
 *   2. time out (false) when no publish ever arrives, leaving the
 *      caller to report UNKNOWN instead of clean;
 *   3. ignore publishes for other URIs.
 */
class LspDiagnosticsFreshnessSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def synthSession(): (LspSession, LspRecordingClient) = {
    val client = new LspRecordingClient(PermissiveWorkspaceEditApplier)
    val session = new LspSession(
      config = LspServerConfig(languageId = "scala", command = "fake", args = Nil),
      projectRoot = "/tmp/fake-project",
      process = null.asInstanceOf[Process],
      server = null.asInstanceOf[org.eclipse.lsp4j.services.LanguageServer],
      client = client,
      serverCapabilities = new ServerCapabilities()
    )
    (session, client)
  }

  private def publish(client: LspRecordingClient, uri: String, diags: Diagnostic*): Unit =
    client.publishDiagnostics(new PublishDiagnosticsParams(uri, diags.toList.asJava))

  private def diag(message: String): Diagnostic =
    new Diagnostic(new Range(new Position(1, 0), new Position(1, 4)), message, DiagnosticSeverity.Error, "fake-server")

  private val uri = "file:///tmp/fake-project/src/Main.scala"

  "publishGeneration" should {

    "be 0 before any publish and increase monotonically per URI" in {
      val (session, client) = synthSession()
      session.publishGeneration(uri) shouldBe 0L
      publish(client, uri, diag("boom"))
      session.publishGeneration(uri) shouldBe 1L
      publish(client, uri) // clean publish still counts as an answer
      session.publishGeneration(uri) shouldBe 2L
      // Another URI's counter is independent.
      session.publishGeneration("file:///tmp/fake-project/src/Other.scala") shouldBe 0L
      Task.unit.map(_ => succeed)
    }
  }

  "waitForDiagnostics(uri, sinceGeneration, timeout)" should {

    "not report fresh for a stale publish — only a NEWER one satisfies the wait" in {
      val (session, client) = synthSession()
      // v1's publish is already in the map (the stale world).
      publish(client, uri, diag("stale error for v1"))
      val genBefore = session.publishGeneration(uri)
      // The v2 publish arrives 300ms into the wait.
      val late = Task.sleep(300.millis).map(_ => publish(client, uri))
      for {
        _ <- Task(late.startUnit())
        fresh <- session.waitForDiagnostics(uri, genBefore, timeoutMs = 3000L)
      } yield {
        fresh shouldBe true
        // And the snapshot now reflects v2 (clean), not v1's error.
        session.diagnosticsFor(uri) shouldBe empty
      }
    }

    "time out with false when no publish ever arrives — unknown, not clean" in {
      val (session, _) = synthSession()
      val startedAt = System.currentTimeMillis()
      for {
        fresh <- session.waitForDiagnostics(uri, sinceGeneration = 0L, timeoutMs = 400L)
      } yield {
        val elapsed = System.currentTimeMillis() - startedAt
        fresh shouldBe false
        elapsed should be >= 400L
        elapsed should be <= 2000L
        // The map default is empty — indistinguishable from clean
        // WITHOUT the generation; the generation is what tells the
        // caller this was never answered.
        session.diagnosticsFor(uri) shouldBe empty
        session.publishGeneration(uri) shouldBe 0L
      }
    }

    "not be satisfied by a publish for a DIFFERENT uri" in {
      val (session, client) = synthSession()
      val otherUri = "file:///tmp/fake-project/src/Other.scala"
      val late = Task.sleep(150.millis).map(_ => publish(client, otherUri, diag("other file's error")))
      for {
        _ <- Task(late.startUnit())
        fresh <- session.waitForDiagnostics(uri, sinceGeneration = 0L, timeoutMs = 600L)
      } yield {
        fresh shouldBe false
        session.publishGeneration(otherUri) shouldBe 1L
      }
    }

    "report fresh immediately when the publish already landed after capture" in {
      val (session, client) = synthSession()
      val genBefore = session.publishGeneration(uri)
      publish(client, uri, diag("compile error"))
      for {
        fresh <- session.waitForDiagnostics(uri, genBefore, timeoutMs = 1000L)
      } yield {
        fresh shouldBe true
        session.diagnosticsFor(uri) should have size 1
      }
    }
  }
}
