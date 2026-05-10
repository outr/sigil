package spec

import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, Topic, TopicEntry, TurnInput}
import sigil.db.SigilDB
import sigil.tooling.{BspManager, BspTestInput, BspTestTool, ToolingCollections, ToolingSigil}

import java.nio.file.Files

/**
 * Coverage for the BSP-tool exception-escape failure mode. When
 * sbt-bsp's `bspBuildTargetTest` crashes mid-call (e.g. because
 * the empty-targets dispatch expanded to include a meta-build
 * target whose `canTest = false`), the resulting exception is
 * supposed to surface as a typed `BspExecResult` with `status =
 * "ERROR"` so the agent's next turn sees a normal tool-result
 * Failure. Pre-fix the exception escaped `executeTyped` and
 * unwound up to `runAgentLoop`, terminating the conversation.
 *
 * This spec validates the error path WITHOUT a real BSP server:
 * a `BspManager` configured against an empty `db.bspBuilds` and
 * a synthetic projectRoot with no `.bsp/<server>.json` file
 * raises an `IllegalStateException` from `manager.session`. That
 * is the same shape the agent loop must tolerate — the tool's
 * executeTyped MUST translate it into a typed `BspExecResult`
 * sentinel rather than letting the throw escape.
 */
class BspTestToolErrorPathSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private class TestDB(directory: Option[java.nio.file.Path],
                       storeManager: lightdb.store.CollectionManager,
                       appUpgrades: List[lightdb.upgrade.DatabaseUpgrade] = Nil)
    extends SigilDB(directory, storeManager, appUpgrades) with ToolingCollections

  private def freshSigil(): ToolingSigil = {
    SpaceId.register(RW.static[SpaceId](GlobalSpace))
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(s"db/test/BspTestToolErrorPathSpec-${rapid.Unique()}"))))
    new ToolingSigil {
      override type DB = TestDB
      override protected def buildDB(directory: Option[java.nio.file.Path],
                                     storeManager: lightdb.store.CollectionManager,
                                     appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
        new TestDB(directory, storeManager, appUpgrades)
      override def providerFor(modelId: lightdb.id.Id[sigil.db.Model],
                                chain: List[sigil.participant.ParticipantId]): rapid.Task[sigil.provider.Provider] =
        rapid.Task.error(new RuntimeException("provider unused in this spec"))
    }
  }

  /** Synthetic test ParticipantId. */
  private case class TestCallerId(value: String) extends sigil.participant.ParticipantId

  private def turnContext(sigil: _root_.sigil.Sigil): TurnContext = {
    val convId = Conversation.id(s"bsp-error-${rapid.Unique()}")
    val topic  = TopicEntry(
      id      = _root_.sigil.conversation.Topic.id(s"topic-${rapid.Unique()}"),
      label   = "spec",
      summary = "spec"
    )
    TurnContext(
      sigil        = sigil,
      chain        = List(TestCallerId("caller-1")),
      conversation = Conversation(topics = List(topic), _id = convId),
      turnInput    = TurnInput(conversationId = convId)
    )
  }

  /** Project root that is guaranteed to have no `.bsp/<server>.json`
    * — `BspManager.session` will fail with IllegalStateException. */
  private def emptyProjectRoot(): String = {
    val p = Files.createTempDirectory(s"bsp-empty-${rapid.Unique()}-")
    p.toAbsolutePath.normalize.toString
  }

  "BspTestTool error path" should {

    "translate a session-spawn failure into a BspExecResult sentinel (NOT an unrecovered exception)" in {
      val sigil   = freshSigil()
      val booted  = sigil.instance.map(_ => sigil)
      booted.flatMap { _ =>
        val manager = new BspManager(sigil.asInstanceOf[_root_.sigil.Sigil { type DB <: SigilDB & ToolingCollections }])
        val tool    = new BspTestTool(manager)
        val ctx     = turnContext(sigil)
        val root    = emptyProjectRoot()
        // Attempt the tool call; the spawn will fail with no config
        // and no .bsp discovery file. Pre-fix the tool's onError
        // re-throws and the exception escapes; post-fix we get a
        // typed BspExecResult with status="ERROR".
        tool.invoke(BspTestInput(projectRoot = root), ctx).attempt.map { result =>
          // Cleanup the synthetic project dir.
          try Files.delete(java.nio.file.Path.of(root)) catch { case _: Throwable => () }
          result.isSuccess shouldBe true
          val res = result.get
          res.status shouldBe "ERROR"
          res.stderr should not be empty
        }.flatMap(a => sigil.shutdown.map(_ => a))
      }
    }
  }
}
