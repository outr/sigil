package spec

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either as LspEither
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tooling.{LspRecordingClient, WorkspaceEditApplier}

import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for the LSP-side notification forwarding from bug #118.
 *
 *   - `$/progress` (`WorkDoneProgressBegin` / `Report` / `End`):
 *     surfaces title / message / percentage through the status
 *     callback.
 *   - `window/logMessage`: routes message text through the callback.
 *   - `window/showMessage`: routes message text through the callback.
 *
 * Pre-fix `notifyProgress` only tracked the token lifecycle and
 * `logMessage` / `showMessage` were no-ops — long Metals operations
 * surfaced nothing in the chip beyond a silent pulse dot.
 */
class LspProgressForwardingSpec extends AnyWordSpec with Matchers {

  private val applier: WorkspaceEditApplier = (_: WorkspaceEdit) => true

  private def newClient(captured: AtomicReference[List[String]]): LspRecordingClient = {
    val c = new LspRecordingClient(applier)
    c.setStatusCallback(Some(text => captured.updateAndGet(prev => prev :+ text)))
    c
  }

  private def workDoneBegin(title: String, message: Option[String] = None, percent: Option[Int] = None): ProgressParams = {
    val begin = new WorkDoneProgressBegin()
    begin.setTitle(title)
    message.foreach(begin.setMessage)
    percent.foreach(p => begin.setPercentage(java.lang.Integer.valueOf(p)))
    val params = new ProgressParams()
    params.setToken(LspEither.forLeft[String, Integer]("test-token"))
    params.setValue(LspEither.forLeft[WorkDoneProgressNotification, Object](begin))
    params
  }

  private def workDoneReport(message: String, percent: Option[Int] = None): ProgressParams = {
    val report = new WorkDoneProgressReport()
    report.setMessage(message)
    percent.foreach(p => report.setPercentage(java.lang.Integer.valueOf(p)))
    val params = new ProgressParams()
    params.setToken(LspEither.forLeft[String, Integer]("test-token"))
    params.setValue(LspEither.forLeft[WorkDoneProgressNotification, Object](report))
    params
  }

  private def workDoneEnd(message: Option[String] = None): ProgressParams = {
    val end = new WorkDoneProgressEnd()
    message.foreach(end.setMessage)
    val params = new ProgressParams()
    params.setToken(LspEither.forLeft[String, Integer]("test-token"))
    params.setValue(LspEither.forLeft[WorkDoneProgressNotification, Object](end))
    params
  }

  "LspRecordingClient.notifyProgress" should {

    "surface WorkDoneProgressBegin's title through the status callback" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.notifyProgress(workDoneBegin("Indexing", message = Some("scala sources")))
      captured.get() shouldBe List("Indexing — scala sources")
    }

    "surface WorkDoneProgressReport with percentage" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.notifyProgress(workDoneReport("47 of 124 files", percent = Some(38)))
      captured.get().head should include("47 of 124 files")
      captured.get().head should include("38%")
    }

    "surface WorkDoneProgressReport without percentage" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.notifyProgress(workDoneReport("Compiling"))
      captured.get() shouldBe List("Compiling")
    }

    "surface WorkDoneProgressEnd's message when present" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.notifyProgress(workDoneEnd(message = Some("Done")))
      captured.get() shouldBe List("Done")
    }

    "be silent on WorkDoneProgressEnd with no message" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.notifyProgress(workDoneEnd())
      captured.get() shouldBe Nil
    }
  }

  "LspRecordingClient.logMessage" should {
    "route message text through the status callback" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.logMessage(new MessageParams(MessageType.Info, "Indexing scala/java sources"))
      captured.get() shouldBe List("Indexing scala/java sources")
    }
  }

  "LspRecordingClient.showMessage" should {
    "route message text through the status callback" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.showMessage(new MessageParams(MessageType.Info, "Importing build via sbt-bloop"))
      captured.get() shouldBe List("Importing build via sbt-bloop")
    }
  }
}
