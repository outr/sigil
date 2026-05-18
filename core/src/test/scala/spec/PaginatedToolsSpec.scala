package spec

import fabric.io.JsonParser
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.{Message, ToolInvoke, ToolResults}
import sigil.tool.fs.{BashLine, BashTool, GlobEntry, GlobTool, GrepNode, GrepTool, FileSystemContext, LocalFileSystemContext, WriteFileTool}
import sigil.tool.model.{BashInput, GlobInput, GrepInput, WriteFileInput, ResponseContent}
import sigil.tool.output.{JsonPagedResult, NextPageInput, NextPageTool, PaginatedTool, QueryToolOutputInput, QueryToolOutputTool}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Coverage for [[PaginatedTool]]'s drain + read pipeline and the
 * three migrated bulk-result tools (grep / glob / bash). Tree-
 * shaped grep navigation goes through `next_page` against a file
 * node's id; flat glob / bash paginate at the top level.
 *
 * The framework writes one [[sigil.tool.output.ToolOutputNode]]
 * row per emitted [[sigil.tool.output.Node]]; the first-page
 * [[JsonPagedResult]] is what the tool's `ToolResults` emission
 * carries inline. Subsequent pages land via [[NextPageTool]] /
 * [[QueryToolOutputTool]].
 */
class PaginatedToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def withTempDir[T](body: (FileSystemContext, Path) => Task[T]): Task[T] = Task.defer {
    val dir = Files.createTempDirectory("sigil-paginated-")
    val ctx = new LocalFileSystemContext(Some(dir))
    body(ctx, dir).guarantee(Task {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def turnContext(callId: lightdb.id.Id[sigil.event.Event]): TurnContext = {
    val convId = Conversation.id(s"paginated-${rapid.Unique()}")
    val topicId = sigil.conversation.Topic.id(s"paginated-topic-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(topicId, "test", "test")),
      _id = convId
    )
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      currentToolInvokeId = Some(callId)
    )
  }

  /**
   * Pull the first page's `JsonPagedResult` out of the emitted
   * `ToolResults` event.
   */
  private def firstPage(events: List[sigil.event.Event]): JsonPagedResult =
    events.collectFirst {
      case t: ToolResults if t.typed.isDefined => t.typed.get.as[JsonPagedResult]
    }.getOrElse(throw new RuntimeException(s"no typed ToolResults found in $events"))

  "GrepTool (paginated, tree-shaped)" should {
    "emit one top-level node per file with at least one match" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx = turnContext(callId)
      for {
        _ <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "alpha\nbeta\nALPHA"), ctx).toList
        _ <- new WriteFileTool(fs).execute(WriteFileInput("b.scala", "beta\nalpha"), ctx).toList
        out <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "(?i)alpha"), ctx).toList
      } yield {
        val page = firstPage(out)
        // Two files with matches → two top-level nodes.
        page.items.size shouldBe 2
        // Each top-level item carries `hasChildren = true` (per-file children).
        page.hasChildren.toSet shouldBe Set(true)
        // Each item's payload is a FileMatch variant carrying matchCount.
        val fileMatches = page.items.map(_.as[GrepNode])
        fileMatches.collect { case f: GrepNode.FileMatch => f.matchCount }.sum shouldBe 3
      }
    }

    "expose per-file matches when next_page is called against a file node id" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx = turnContext(callId)
      for {
        _ <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "alpha\nbeta\nALPHA"), ctx).toList
        out <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "(?i)alpha"), ctx).toList
        page = firstPage(out)
        fileNodeId = page.nodeIds.head
        // Expand that file's children via next_page.
        children <- NextPageTool.invoke(NextPageInput(referenceId = fileNodeId, page = 0, pageSize = 50), ctx)
      } yield {
        children.items.size shouldBe 2 // two ALPHA matches in a.scala
        val lineMatches = children.items.map(_.as[GrepNode]).collect { case l: GrepNode.LineMatch => l }
        lineMatches.map(_.lineNumber).toSet shouldBe Set(1, 3)
      }
    }
  }

  "GlobTool (paginated, flat)" should {
    "list files in pages" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx = turnContext(callId)
      for {
        _ <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "x"), ctx).toList
        _ <- new WriteFileTool(fs).execute(WriteFileInput("b.scala", "x"), ctx).toList
        _ <- new WriteFileTool(fs).execute(WriteFileInput("c.txt", "x"), ctx).toList
        out <- new GlobTool(fs).execute(GlobInput(basePath = ".", pattern = "*.scala"), ctx).toList
      } yield {
        val page = firstPage(out)
        val entries = page.items.map(_.as[GlobEntry].path)
        entries.toSet shouldBe Set("a.scala", "b.scala")
        page.hasMore shouldBe false
      }
    }
  }

  "BashTool (paginated lines)" should {
    "emit stdout lines followed by an Exit row" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx = turnContext(callId)
      for {
        out <- new BashTool(fs).execute(BashInput("printf 'one\\ntwo\\nthree'"), ctx).toList
      } yield {
        val page = firstPage(out)
        val lines = page.items.map(_.as[BashLine])
        // Exactly three Stdout rows + one Exit row.
        lines.count {
          case _: BashLine.Stdout => true
          case _ => false
        } shouldBe 3
        lines.collect { case e: BashLine.Exit => e.code } shouldBe List(0)
      }
    }

    "carry non-zero exit codes in the Exit row" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx = turnContext(callId)
      new BashTool(fs).execute(BashInput("exit 42"), ctx).toList.map { events =>
        val page = firstPage(events)
        page.items.map(_.as[BashLine]).collect { case e: BashLine.Exit => e.code } shouldBe List(42)
      }
    }
  }

  "NextPageTool" should {
    "page past the first batch when the result exceeds firstPageSize" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx = turnContext(callId)
      // 120 files; firstPageSize is 50 (default) → first page has 50, hasMore = true.
      val writes = (1 to 120).map(i => new WriteFileTool(fs).execute(WriteFileInput(f"f$i%03d.scala", "x"), ctx).toList)
      for {
        _ <- Task.sequence(writes.toList)
        out <- new GlobTool(fs).execute(GlobInput(basePath = ".", pattern = "*.scala"), ctx).toList
        page = firstPage(out)
        next <- NextPageTool.invoke(NextPageInput(referenceId = callId.value, page = 1, pageSize = 50), ctx)
      } yield {
        page.items.size shouldBe 50
        page.hasMore shouldBe true
        next.items.size shouldBe 50
        next.page shouldBe 1
      }
    }
  }

  "QueryToolOutputTool" should {
    "filter rows by containsText" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx = turnContext(callId)
      for {
        _ <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "alpha\nbeta\nALPHA"), ctx).toList
        _ <- new WriteFileTool(fs).execute(WriteFileInput("b.scala", "beta\nalpha"), ctx).toList
        _ <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "(?i)alpha"), ctx).toList
        // Query the call's rows for the one whose payload mentions "a.scala".
        out <- QueryToolOutputTool.invoke(
          QueryToolOutputInput(callId = callId.value, containsText = Some("a.scala"), level = Some(0)),
          ctx
        )
      } yield out.items.map(_.as[GrepNode]).collect { case f: GrepNode.FileMatch => f.filePath } shouldBe List("a.scala")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
