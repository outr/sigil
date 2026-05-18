package spec

import ch.epfl.scala.bsp4j.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tooling.BspRecordingBuildClient

import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for [[BspRecordingBuildClient]]'s status forwarding —
 * BSP server notifications (logMessage, taskStart / taskProgress /
 * taskFinish, showMessage) route through the per-call status
 * callback so the active tool's chip surfaces what the build
 * server is doing instead of looking frozen.
 *
 * Pre-fix the client recorded these notifications locally
 * (`logs`, `activeTasks`) but the data never reached the agent
 * surface — tools that ran long (`bsp_compile`,
 * `bsp_dependency_modules`) showed a silent pulse dot for the
 * entire duration.
 */
class BspNotificationForwardingSpec extends AnyWordSpec with Matchers {

  private def newClient(captured: AtomicReference[List[String]]): BspRecordingBuildClient = {
    val c = new BspRecordingBuildClient
    c.setStatusCallback(Some(text => captured.updateAndGet(prev => prev :+ text)))
    c
  }

  "BspRecordingBuildClient.onBuildLogMessage" should {

    "route the message text through the status callback" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      val params = new LogMessageParams(MessageType.INFO, "Resolving dependencies for widge-server")
      client.onBuildLogMessage(params)
      captured.get() shouldBe List("Resolving dependencies for widge-server")
    }

    "still record the message into the local queue (recording behaviour preserved)" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      val params = new LogMessageParams(MessageType.INFO, "Walking 8 build targets")
      client.onBuildLogMessage(params)
      client.drainLogs().map(_.getMessage) shouldBe List("Walking 8 build targets")
    }

    "ignore empty / null messages" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.onBuildLogMessage(new LogMessageParams(MessageType.INFO, ""))
      captured.get() shouldBe Nil
    }
  }

  "BspRecordingBuildClient.onBuildTaskProgress" should {

    "route the progress message with a percentage when total > 0" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      val params = new TaskProgressParams(new TaskId("compile-task-1"))
      params.setMessage("compiling files")
      params.setProgress(12L)
      params.setTotal(47L)
      client.onBuildTaskProgress(params)
      captured.get().head should include("compiling files")
      // 12 / 47 = 0.255… → rounds to 26%
      captured.get().head should include("26%")
    }

    "route just the message when total is zero or missing" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      val params = new TaskProgressParams(new TaskId("compile-task-2"))
      params.setMessage("indexing sources")
      client.onBuildTaskProgress(params)
      captured.get() shouldBe List("indexing sources")
    }
  }

  "BspRecordingBuildClient.onBuildTaskStart" should {

    "route the start message when present" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      val params = new TaskStartParams(new TaskId("compile-task-3"))
      params.setMessage("Starting compile for widge-server")
      client.onBuildTaskStart(params)
      captured.get() shouldBe List("Starting compile for widge-server")
    }
  }

  "BspRecordingBuildClient.onBuildTaskFinish" should {

    "combine message + status when both present" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      val params = new TaskFinishParams(new TaskId("compile-task-4"), StatusCode.OK)
      params.setMessage("Compiled widge-server")
      client.onBuildTaskFinish(params)
      captured.get().head should include("Compiled widge-server")
      captured.get().head should include("OK")
    }

    "surface the status alone when no message" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      val params = new TaskFinishParams(new TaskId("compile-task-5"), StatusCode.ERROR)
      client.onBuildTaskFinish(params)
      captured.get() shouldBe List("ERROR")
    }
  }

  "BspRecordingBuildClient.onBuildShowMessage" should {

    "route the message through the status callback" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      val params = new ShowMessageParams(MessageType.INFO, "Importing build via sbt-bloop")
      client.onBuildShowMessage(params)
      captured.get() shouldBe List("Importing build via sbt-bloop")
    }
  }

  "status callback lifecycle" should {

    "no-op when no callback is registered" in {
      val client = new BspRecordingBuildClient
      noException should be thrownBy
        client.onBuildLogMessage(new LogMessageParams(MessageType.INFO, "hello"))
    }

    "no-op after callback cleared" in {
      val captured = new AtomicReference[List[String]](Nil)
      val client = newClient(captured)
      client.setStatusCallback(None)
      client.onBuildLogMessage(new LogMessageParams(MessageType.INFO, "hello"))
      captured.get() shouldBe Nil
    }
  }
}
