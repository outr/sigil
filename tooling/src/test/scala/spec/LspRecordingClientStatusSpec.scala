package spec

import com.google.gson.JsonObject
import org.eclipse.lsp4j.WorkspaceEdit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tooling.{LspRecordingClient, WorkspaceEditApplier}

import java.util.concurrent.atomic.AtomicReference

class LspRecordingClientStatusSpec extends AnyWordSpec with Matchers {

  "LspRecordingClient.metalsStatus (#98)" should {

    "route the `text` field through the registered status callback" in {
      val applier: WorkspaceEditApplier = (_: WorkspaceEdit) => true
      val client = new LspRecordingClient(applier)
      val captured = new AtomicReference[Option[String]](None)
      client.setStatusCallback(Some(text => captured.set(Some(text))))

      val params = new JsonObject
      params.addProperty("text", "indexing scala/java sources")
      params.addProperty("show", true)
      client.metalsStatus(params)

      captured.get() shouldBe Some("indexing scala/java sources")
    }

    "ignore notifications with empty/missing text" in {
      val applier: WorkspaceEditApplier = (_: WorkspaceEdit) => true
      val client = new LspRecordingClient(applier)
      val captured = new AtomicReference[Option[String]](None)
      client.setStatusCallback(Some(text => captured.set(Some(text))))

      val params = new JsonObject
      params.addProperty("text", "")
      client.metalsStatus(params)
      captured.get() shouldBe None

      // No `text` field at all — must not throw, must not deliver.
      val empty = new JsonObject
      client.metalsStatus(empty)
      captured.get() shouldBe None
    }

    "no-op when no callback is registered (callback was never set)" in {
      val applier: WorkspaceEditApplier = (_: WorkspaceEdit) => true
      val client = new LspRecordingClient(applier)
      // No setStatusCallback call.
      val params = new JsonObject
      params.addProperty("text", "indexing")
      noException should be thrownBy client.metalsStatus(params)
    }

    "no-op after the callback is cleared (None)" in {
      val applier: WorkspaceEditApplier = (_: WorkspaceEdit) => true
      val client = new LspRecordingClient(applier)
      val captured = new AtomicReference[Option[String]](None)
      client.setStatusCallback(Some(text => captured.set(Some(text))))
      client.setStatusCallback(None)

      val params = new JsonObject
      params.addProperty("text", "indexing")
      client.metalsStatus(params)
      captured.get() shouldBe None
    }
  }
}
