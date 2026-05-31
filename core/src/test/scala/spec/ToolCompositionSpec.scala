package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.ToolOutcome
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.fs.{FileSystemContext, GlobTool, GrepNode, GrepTool, LocalFileSystemContext, WriteFileTool}
import sigil.tool.model.{GlobInput, GrepInput, WriteFileInput}
import sigil.tool.output.JsonPagedResult

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Tool composition by reference (sigil #322 follow-up): a data tool takes
 * the `callId` of a prior `grep` / `glob` result via `from` and scopes its
 * own work to the files that output surfaced. Tool output is durable
 * point-in-time evidence — the reference reads exactly what the source
 * tool drained, never a re-execution — so "search within the files I just
 * found" composes without re-walking from scratch or trusting that the
 * tree hasn't shifted underneath.
 */
class ToolCompositionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def withTempDir[T](body: (FileSystemContext, Path) => Task[T]): Task[T] = Task.defer {
    val dir = Files.createTempDirectory("sigil-composition-")
    val ctx = new LocalFileSystemContext(Some(dir))
    body(ctx, dir).guarantee(Task {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  /** One TurnContext shared across a composed run so every tool call's
    * rows land under the same conversation (where `from` reads them). */
  private def turnContext(): TurnContext = {
    val convId = Conversation.id(s"composition-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(sigil.conversation.Topic.id(s"composition-topic-${rapid.Unique()}"), "t", "t")),
      _id    = convId
    )
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser),
      conversation = conv,
      turnInput    = TurnInput(ConversationView(conversationId = convId)),
      model        = TestSigil.defaultTestModel
    )
  }

  private def firstPage(signals: List[Signal]): JsonPagedResult =
    signals.collectFirst {
      case d: ToolDelta if d.output.exists(_.isInstanceOf[JsonPagedResult]) =>
        d.output.get.asInstanceOf[JsonPagedResult]
    }.getOrElse(throw new RuntimeException(s"no settling ToolDelta with JsonPagedResult output in $signals"))

  private def fileMatches(page: JsonPagedResult): Set[String] =
    page.items.map(_.as[GrepNode]).collect { case f: GrepNode.FileMatch => f.filePath }.toSet

  private def seed(fs: FileSystemContext, ctx: TurnContext): Task[Unit] =
    for {
      _ <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "needle\nalpha"), ctx, sigil.event.Event.id()).toList
      _ <- new WriteFileTool(fs).execute(WriteFileInput("b.scala", "needle\nbeta"), ctx, sigil.event.Event.id()).toList
      _ <- new WriteFileTool(fs).execute(WriteFileInput("c.txt", "needle\ngamma"), ctx, sigil.event.Event.id()).toList
    } yield ()

  "grep with `from` referencing a prior glob" should {
    "restrict the search to the files that glob surfaced" in withTempDir { (fs, _) =>
      val ctx      = turnContext()
      val globCall = sigil.event.Event.id()
      for {
        _       <- seed(fs, ctx)
        globOut <- new GlobTool(fs).execute(GlobInput(basePath = ".", pattern = "*.scala"), ctx, globCall).toList
        grepOut <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "needle", from = Some(globCall.value)), ctx, sigil.event.Event.id()).toList
      } yield {
        // glob saw only the .scala files...
        firstPage(globOut).items.size shouldBe 2
        // ...so even though c.txt also contains "needle", the scoped grep
        // ignores it.
        fileMatches(firstPage(grepOut)) shouldBe Set("a.scala", "b.scala")
      }
    }

    "match every file when `from` is absent (control)" in withTempDir { (fs, _) =>
      val ctx = turnContext()
      for {
        _       <- seed(fs, ctx)
        grepOut <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "needle"), ctx, sigil.event.Event.id()).toList
      } yield fileMatches(firstPage(grepOut)) shouldBe Set("a.scala", "b.scala", "c.txt")
    }
  }

  "grep with `from` referencing a prior grep" should {
    "narrow within the earlier match set" in withTempDir { (fs, _) =>
      val ctx       = turnContext()
      val firstGrep = sigil.event.Event.id()
      for {
        _    <- seed(fs, ctx)
        g1   <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "needle"), ctx, firstGrep).toList
        // Of the files containing "needle" (a, b, c), only a.scala has "alpha".
        g2   <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "alpha", from = Some(firstGrep.value)), ctx, sigil.event.Event.id()).toList
      } yield {
        fileMatches(firstPage(g1)) shouldBe Set("a.scala", "b.scala", "c.txt")
        fileMatches(firstPage(g2)) shouldBe Set("a.scala")
      }
    }
  }

  "glob with `from` referencing a prior glob" should {
    "intersect the pattern with the referenced file set" in withTempDir { (fs, _) =>
      val ctx       = turnContext()
      val firstGlob = sigil.event.Event.id()
      for {
        _   <- seed(fs, ctx)
        gAll <- new GlobTool(fs).execute(GlobInput(basePath = ".", pattern = "*"), ctx, firstGlob).toList
        // Restrict a *.scala listing to the files the broad glob saw.
        gScoped <- new GlobTool(fs).execute(GlobInput(basePath = ".", pattern = "*.scala", from = Some(firstGlob.value)), ctx, sigil.event.Event.id()).toList
      } yield {
        val all = firstPage(gAll).items.map(_.as[sigil.tool.fs.GlobEntry].path).toSet
        Set("a.scala", "b.scala", "c.txt").subsetOf(all) shouldBe true
        firstPage(gScoped).items.map(_.as[sigil.tool.fs.GlobEntry].path).toSet shouldBe Set("a.scala", "b.scala")
      }
    }
  }

  "grep with an unresolvable `from`" should {
    "settle with a recoverable failure rather than silently matching nothing" in withTempDir { (fs, _) =>
      val ctx = turnContext()
      for {
        _   <- seed(fs, ctx)
        out <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "needle", from = Some("no-such-reference")), ctx, sigil.event.Event.id()).toList
      } yield {
        val failure = out.collectFirst {
          case d: ToolDelta => d.outcome.collect { case f: ToolOutcome.Failure => f }
        }.flatten.getOrElse(fail(s"no settling failure ToolDelta in $out"))
        failure.recoverable shouldBe true
        failure.reason should include("not found")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
