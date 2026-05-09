package spec

import fabric.rw.*
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId}
import sigil.tooling.{LspRecordingClient, WorkspaceEditApplier}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

/**
 * Coverage for sigil bug #93 — `LspRecordingClient` extends lsp4j's
 * `LanguageClient` (a Java interface with default methods). Without
 * explicit Scala overrides for every default method, lsp4j's
 * `AnnotationUtil.findRpcMethods` reflection scan double-counts
 * synthetic forwarders + inherited defaults and throws
 * `Duplicate RPC method workspace/configuration` at launcher
 * construction time.
 *
 * Pre-#93 the generic `lsp_*` family was rarely exercised so the
 * latent issue went unnoticed. After bug #88 wired Metals into the
 * generic LSP path via `LspServerConfig("scala", ...)`, every
 * `lsp_*` call hit the duplicate-method error and the language
 * server never connected.
 *
 * This spec asserts the launcher constructs cleanly — same shape
 * `LspSession.spawn` uses internally.
 */
class LspRecordingClientLauncherSpec extends AnyWordSpec with Matchers {
  SpaceId.register(RW.static[SpaceId](GlobalSpace))

  "LspRecordingClient" should {

    "construct an lsp4j Launcher without a duplicate-RPC-method error (#93)" in {
      val applier: WorkspaceEditApplier = (_: WorkspaceEdit) => true
      val client = new LspRecordingClient(applier)
      val in = new ByteArrayInputStream(Array.emptyByteArray)
      val out = new ByteArrayOutputStream()
      // The construction is what blew up pre-fix — the JSON-RPC
      // method-scan on `client` enumerates default methods of
      // LanguageClient and would throw on duplicates. Use the
      // Builder shape mirroring `LspSession.spawn`.
      val launcher = new Launcher.Builder[LanguageServer]()
        .setLocalService(client)
        .setRemoteInterface(classOf[LanguageServer])
        .setInput(in)
        .setOutput(out)
        .create()
      launcher should not be null
      // Don't `startListening` — the client expects a real server
      // on the other side; we only validate the construction phase.
    }
  }
}
