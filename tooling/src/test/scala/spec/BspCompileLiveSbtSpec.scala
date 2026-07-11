package spec

import fabric.rw.RW
import org.scalatest.{Args, Status}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.SigilDB
import sigil.event.Event
import sigil.tool.ToolContext
import sigil.tooling.{BspBuildConfig, BspCompileInput, BspCompileTool, BspManager, ToolingCollections, ToolingSigil}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * End-to-end `bsp_compile` against a REAL sbt BSP server on a fixture
 * workspace — the coverage the field failures kept asking for: a broken
 * build must yield per-file diagnostics (WHERE it failed), not just an
 * ERROR status, even when sbt errors the compile request wholesale; and
 * a fixed build must return to OK in the same session.
 *
 * Slow (sbt-BSP boot) — gated behind SIGIL_SLOW like the other
 * multi-minute specs, so it runs via test-all.sh and the weekly CI full
 * battery. Also self-skips when `sbt` isn't on PATH.
 */
class BspCompileLiveSbtSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  override implicit protected val testTimeout: FiniteDuration = 10.minutes

  override def run(testName: Option[String], args: Args): Status =
    LiveProbe.requireSlowEnabled(this).getOrElse(super.run(testName, args))

  private class TestDB(directory: Option[Path],
                       storeManager: lightdb.store.CollectionManager,
                       appUpgrades: List[lightdb.upgrade.DatabaseUpgrade] = Nil)
    extends SigilDB(directory, storeManager, appUpgrades) with ToolingCollections

  private def freshSigil(): ToolingSigil = {
    SpaceId.register(RW.static[SpaceId](GlobalSpace))
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(s"db/test/BspCompileLiveSbtSpec-${rapid.Unique()}"))))
    new ToolingSigil {
      override type DB = TestDB
      override protected def buildDB(directory: Option[Path],
                                     storeManager: lightdb.store.CollectionManager,
                                     appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
        new TestDB(directory, storeManager, appUpgrades)
      override def modelResolver: sigil.provider.ModelResolver = _ => None
    }
  }

  private case class TestCallerId(value: String) extends sigil.participant.ParticipantId

  private def toolContext(sigil: _root_.sigil.Sigil, toolName: _root_.sigil.tool.ToolName): ToolContext = {
    val convId = Conversation.id(s"bsp-live-${rapid.Unique()}")
    val topic = TopicEntry(
      id      = _root_.sigil.conversation.Topic.id(s"topic-${rapid.Unique()}"),
      label   = "spec",
      summary = "spec"
    )
    val turn = TurnContext(
      sigil        = sigil,
      chain        = List(TestCallerId("caller-1")),
      conversation = Conversation(topics = List(topic), _id = convId),
      turnInput    = TurnInput(conversationId = convId),
      model        = TestSigil.defaultTestModel
    )
    ToolContext(turn, Event.id(), toolName)
  }

  private val sbtOnPath: Boolean =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparatorChar)
      .exists(dir => Files.isExecutable(Path.of(dir, "sbt")))

  /** Minimal sbt project (bundled Scala 2.12 — no extra downloads
    * beyond the launcher the repo already pins) with two files. */
  private def writeFixture(broken: Boolean): Path = {
    val dir = Files.createTempDirectory(s"bsp-live-sbt-${rapid.Unique()}-")
    Files.createDirectories(dir.resolve("project"))
    Files.createDirectories(dir.resolve("src/main/scala"))
    Files.writeString(dir.resolve("project/build.properties"), "sbt.version=1.12.13\n")
    Files.writeString(dir.resolve("build.sbt"), """name := "bsp-fixture"""" + "\n")
    writeSources(dir, broken)
    dir
  }

  private def writeSources(dir: Path, broken: Boolean): Unit =
    if (broken) {
      Files.writeString(dir.resolve("src/main/scala/Bad1.scala"),
        "object Bad1 { def broken(: Int = 1 }\n")
      Files.writeString(dir.resolve("src/main/scala/Bad2.scala"),
        "object Bad2 { val x = \n")
    } else {
      Files.writeString(dir.resolve("src/main/scala/Bad1.scala"),
        "object Bad1 { def fine: Int = 1 }\n")
      Files.writeString(dir.resolve("src/main/scala/Bad2.scala"),
        "object Bad2 { val x = 2 }\n")
    }

  private def deleteRecursively(dir: Path): Unit = {
    import scala.jdk.CollectionConverters.*
    try Files.walk(dir).iterator().asScala.toList.reverse.foreach(p => try Files.delete(p) catch { case _: Throwable => () })
    catch { case _: Throwable => () }
  }

  "bsp_compile against a real sbt BSP server" should {

    "surface per-file diagnostics for a broken workspace, then OK once fixed" in {
      if (!sbtOnPath) cancel("sbt not on PATH — live sbt-BSP test")
      val sigil = freshSigil()
      val dir   = writeFixture(broken = true)
      val root  = dir.toAbsolutePath.normalize.toString
      sigil.instance.flatMap { _ =>
        val manager = new BspManager(sigil.asInstanceOf[_root_.sigil.Sigil { type DB <: SigilDB & ToolingCollections }])
        val tool    = new BspCompileTool(manager)
        val ctx     = toolContext(sigil, tool.name)
        val cleanup = Task {
          try manager.shutdown(root).sync() catch { case _: Throwable => () }
          deleteRecursively(dir)
        }
        (for {
          _      <- sigil.withDB(_.bspBuilds.transaction(_.upsert(
                      BspBuildConfig(projectRoot = root, command = "sbt", args = List("-bsp"),
                        _id = BspBuildConfig.idFor(root)))))
          broken <- tool.invoke(BspCompileInput(projectRoot = root), ctx)
          _      = Task(writeSources(dir, broken = false)).sync()
          fixed  <- tool.invoke(BspCompileInput(projectRoot = root), ctx)
        } yield {
          withClue(s"broken result: status=${broken.status} cause=${broken.cause} " +
            s"diags=${broken.diagnostics.map(d => s"${d.filePath}:${d.range}")}: ") {
            broken.status shouldBe "ERROR"
            // The whole point: WHERE it failed, not just THAT it failed.
            broken.diagnostics.size should be >= 2
            val files = broken.diagnostics.map(_.filePath)
            files.exists(_.endsWith("Bad1.scala")) shouldBe true
            files.exists(_.endsWith("Bad2.scala")) shouldBe true
            broken.diagnostics.foreach(_.message.trim should not be empty)
          }
          withClue(s"fixed result: status=${fixed.status} cause=${fixed.cause}: ") {
            fixed.status shouldBe "OK"
            fixed.diagnostics.filter(d => d.filePath.endsWith("Bad1.scala") || d.filePath.endsWith("Bad2.scala")) shouldBe empty
          }
        }).guarantee(cleanup).flatMap(a => sigil.shutdown.map(_ => a))
      }
    }
  }
}
