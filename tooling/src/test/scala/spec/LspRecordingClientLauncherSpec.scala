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
