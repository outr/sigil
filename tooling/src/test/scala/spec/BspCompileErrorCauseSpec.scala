package spec

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, LogMessageParams, MessageType}
import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.SigilDB
import sigil.event.Event
import sigil.tool.ToolContext
import sigil.tooling.{BspCompileInput, BspCompileTool, BspManager, BspToolSupport, ToolingCollections, ToolingSigil}

import java.nio.file.Files

/**
 * A `bsp_compile` ERROR must always carry actionable content. The
 * observed failure shape — `{status: "ERROR", targetCount: 0,
 * diagnostics: []}` on a workspace with real compile errors — left the
 * agent blind: nothing in its loop could surface WHAT failed, so it
 * thrashed (re-compile, re-list targets, restart the server) without
 * converging.
 *
 * Pins:
 *   1. A request-level failure (session spawn / build import) carries
 *      its reason in `cause` instead of a bare ERROR.
 *   2. An explicit target URI that doesn't resolve produces a message
 *      naming the unmatched URI and the valid set (unit level — the
 *      shared `withTargets` validation).
 *   3. A failed compile with no structured diagnostics falls back to a
 *      bounded tail of the build server's log output, preferring
 *      error-severity lines (unit level — the extracted seam).
 */
class BspCompileErrorCauseSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private class TestDB(directory: Option[java.nio.file.Path],
                       storeManager: lightdb.store.CollectionManager,
                       appUpgrades: List[lightdb.upgrade.DatabaseUpgrade] = Nil)
    extends SigilDB(directory, storeManager, appUpgrades) with ToolingCollections

  private def freshSigil(): ToolingSigil = {
    SpaceId.register(RW.static[SpaceId](GlobalSpace))
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(s"db/test/BspCompileErrorCauseSpec-${rapid.Unique()}"))))
    new ToolingSigil {
      override type DB = TestDB
      override protected def buildDB(directory: Option[java.nio.file.Path],
                                     storeManager: lightdb.store.CollectionManager,
                                     appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
        new TestDB(directory, storeManager, appUpgrades)
      override def modelResolver: sigil.provider.ModelResolver = _ => None
    }
  }

  private case class TestCallerId(value: String) extends sigil.participant.ParticipantId

  private def turnContext(sigil: _root_.sigil.Sigil): TurnContext = {
    val convId = Conversation.id(s"bsp-cause-${rapid.Unique()}")
    val topic = TopicEntry(
      id = _root_.sigil.conversation.Topic.id(s"topic-${rapid.Unique()}"),
      label = "spec",
      summary = "spec"
    )
    TurnContext(
      sigil = sigil,
      chain = List(TestCallerId("caller-1")),
      conversation = Conversation(topics = List(topic), _id = convId),
      turnInput = TurnInput(conversationId = convId),
      model = TestSigil.defaultTestModel
    )
  }

  private def emptyProjectRoot(): String = {
    val p = Files.createTempDirectory(s"bsp-cause-${rapid.Unique()}-")
    p.toAbsolutePath.normalize.toString
  }

  "bsp_compile on a request-level failure" should {

    "carry the reason in `cause` instead of a bare contentless ERROR" in {
      val sigil = freshSigil()
      sigil.instance.flatMap { _ =>
        val manager = new BspManager(sigil.asInstanceOf[_root_.sigil.Sigil { type DB <: SigilDB & ToolingCollections }])
        val tool = new BspCompileTool(manager)
        val ctx = turnContext(sigil)
        val root = emptyProjectRoot()
        tool.invoke(BspCompileInput(projectRoot = root), ToolContext(ctx, Event.id(), tool.name)).attempt.map { result =>
          try Files.delete(java.nio.file.Path.of(root))
          catch { case _: Throwable => () }
          result.isSuccess shouldBe true
          val res = result.get
          res.status shouldBe "ERROR"
          withClue(s"cause=${res.cause}: ") {
            res.cause should not be empty
            res.cause.get should include("BSP error")
          }
        }.flatMap(a => sigil.shutdown.map(_ => a))
      }
    }
  }

  "withTargets target validation" should {

    val workspace = List(
      new BuildTargetIdentifier("file:/proj/#core/Compile"),
      new BuildTargetIdentifier("file:/proj/#core/Test")
    )

    "name the unmatched URI and the valid set" in Task {
      val out = BspToolSupport.validateRequestedTargets(List("file:/proj/#all/Compile"), workspace)
      out.isLeft shouldBe true
      val reason = out.swap.toOption.get
      reason should include("file:/proj/#all/Compile")
      reason should include("file:/proj/#core/Compile")
      reason should include("file:/proj/#core/Test")
    }

    "pass matching URIs through and expand empty input to the workspace" in Task {
      BspToolSupport.validateRequestedTargets(List("file:/proj/#core/Test"), workspace)
        .map(_.map(_.getUri)) shouldBe Right(List("file:/proj/#core/Test"))
      BspToolSupport.validateRequestedTargets(Nil, workspace)
        .map(_.size) shouldBe Right(2)
    }
  }

  "the failed-request diagnostics path" should {

    import ch.epfl.scala.bsp4j.{Diagnostic, Position, PublishDiagnosticsParams, Range, TextDocumentIdentifier}
    import scala.jdk.CollectionConverters.*
    import sigil.tooling.BspRecordingBuildClient

    def diag(line: Int, msg: String): Diagnostic =
      new Diagnostic(new Range(new Position(line, 0), new Position(line, 10)), msg)
    def publish(client: BspRecordingBuildClient, uri: String, ds: Diagnostic*): Unit =
      client.onBuildPublishDiagnostics(new PublishDiagnosticsParams(
        new TextDocumentIdentifier(uri),
        new BuildTargetIdentifier("file:/proj/#core/Compile"),
        ds.toList.asJava,
        java.lang.Boolean.TRUE
      ))

    "surface diagnostics published before the request failed, alongside the request's own cause" in Task {
      // sbt's BSP errors the JSON-RPC response for some compile failures
      // instead of returning CompileResult(ERROR) — but it has already
      // published per-file diagnostics on the way down. Those must reach
      // the agent (WHERE it failed), with the request failure kept as
      // the cause (THAT it failed).
      val client = new BspRecordingBuildClient
      publish(client, "file:///proj/src/Bad1.scala", diag(3, "']' expected but '}' found"))
      publish(client, "file:///proj/src/Bad2.scala", diag(7, "unclosed comment"))
      val res = BspCompileTool.buildResult(
        "/proj",
        "ERROR",
        2,
        client,
        requestFailure = Some("BSP error: (core / Compile / compileIncremental) Compilation failed"))
      res.status shouldBe "ERROR"
      res.targetCount shouldBe 2
      res.diagnostics.map(_.filePath).toSet shouldBe Set("/proj/src/Bad1.scala", "/proj/src/Bad2.scala")
      res.diagnostics.map(_.message) should contain("unclosed comment")
      res.cause shouldBe Some("BSP error: (core / Compile / compileIncremental) Compilation failed")
    }

    "point at the build tool when the failed request published nothing" in Task {
      val client = new BspRecordingBuildClient
      val res = BspCompileTool.buildResult(
        "/proj",
        "ERROR",
        2,
        client,
        requestFailure = Some("BSP error: Compilation failed"))
      res.diagnostics shouldBe empty
      res.cause should not be empty
      res.cause.get should include("BSP error: Compilation failed")
      res.cause.get should include("published no diagnostics — run the build tool directly")
    }

    "carry the hint alone for a normal ERROR result with no diagnostics and no logs" in Task {
      val client = new BspRecordingBuildClient
      val res = BspCompileTool.buildResult("/proj", "ERROR", 1, client, requestFailure = None)
      res.cause should not be empty
      res.cause.get should include("published no diagnostics — run the build tool directly")
    }
  }

  "the diagnostics-free compile-failure fallback" should {

    def log(t: MessageType, msg: String): LogMessageParams =
      new LogMessageParams(t, msg)

    "prefer error-severity lines from the server output" in Task {
      val cause = BspCompileTool.errorCauseFromLogs(List(
        log(MessageType.INFO, "compiling 12 sources"),
        log(MessageType.ERROR, "Sigil.scala:7822: ',' or ')' expected, but identifier found"),
        log(MessageType.INFO, "done")
      ))
      cause should not be empty
      cause.get should include("Sigil.scala:7822")
      cause.get should not include "compiling 12 sources"
    }

    "fall back to the full log tail when no error-severity lines exist, bounded" in Task {
      val filler = "x" * 1000
      val cause = BspCompileTool.errorCauseFromLogs(
        (1 to 10).toList.map(i => log(MessageType.INFO, s"line-$i $filler"))
      )
      cause should not be empty
      cause.get.length should be < 4300 // bounded tail + prefix
      cause.get should include("line-10")
    }

    "return None when the server logged nothing" in Task {
      BspCompileTool.errorCauseFromLogs(Nil) shouldBe None
      BspCompileTool.errorCauseFromLogs(List(log(MessageType.INFO, "  "))) shouldBe None
    }
  }
}
