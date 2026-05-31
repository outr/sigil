package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.ToolContext
import sigil.tool.fs.{BashLine, BashTool, GlobEntry, GlobTool, GrepNode, GrepTool, FileSystemContext, LocalFileSystemContext, WriteFileTool}
import sigil.tool.model.{BashInput, GlobInput, GrepInput, WriteFileInput}
import sigil.tool.output.{JsonPagedResult, QueryToolOutputInput, QueryToolOutputTool}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import sigil.event.Event

/**
 * Coverage for [[PaginatedTool]]'s drain + read pipeline and the
 * three migrated bulk-result tools (grep / glob / bash). Tree-
 * shaped grep line matches are read via a flat `query_tool_output(level = 1)` over the file
 * node's id; flat glob / bash paginate at the top level.
 *
 * The framework writes one [[sigil.tool.output.ToolOutputNode]]
 * row per emitted [[sigil.tool.output.Node]]; the first-page
 * [[JsonPagedResult]] is what the tool's settling
 * [[sigil.signal.ToolDelta]] carries inline as its `output`.
 * Further entries are read via [[QueryToolOutputTool]] (the retired next_page cursor is gone).
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

  private def turnContext(): TurnContext = {
    val convId  = Conversation.id(s"paginated-${rapid.Unique()}")
    val topicId = sigil.conversation.Topic.id(s"paginated-topic-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(topicId, "test", "test")),
      _id    = convId
    )
    TurnContext(
      sigil                = TestSigil,
      chain                = List(TestUser),
      conversation         = conv,
      turnInput            = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  /** Build a [[ToolContext]] for direct `tool.invoke` calls. The
    * `invokeId` is the tool's pagination call id where it matters
    * (PaginatedTool drains rows keyed by it). */
  private def toolContext(turn: TurnContext, invokeId: lightdb.id.Id[sigil.event.Event], name: sigil.tool.ToolName): ToolContext =
    ToolContext(turn, invokeId, name)

  /** Pull the first page's `JsonPagedResult` out of the settling
    * [[ToolDelta]]'s typed `output`. */
  private def firstPage(signals: List[Signal]): JsonPagedResult =
    signals.collectFirst {
      case d: ToolDelta if d.output.exists(_.isInstanceOf[JsonPagedResult]) =>
        d.output.get.asInstanceOf[JsonPagedResult]
    }.getOrElse(throw new RuntimeException(s"no settling ToolDelta with JsonPagedResult output found in $signals"))

  "GrepTool (paginated, tree-shaped)" should {
    "emit one top-level node per file with at least one match" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx    = turnContext()
      for {
        _    <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "alpha\nbeta\nALPHA"), ctx, sigil.event.Event.id()).toList
        _    <- new WriteFileTool(fs).execute(WriteFileInput("b.scala", "beta\nalpha"), ctx, sigil.event.Event.id()).toList
        out  <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "(?i)alpha"), ctx, callId).toList
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

    "expose per-file line matches via query_tool_output(level = 1)" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx    = turnContext()
      for {
        _    <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "alpha\nbeta\nALPHA"), ctx, sigil.event.Event.id()).toList
        _    <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "(?i)alpha"), ctx, callId).toList
        // The line-match children are reached by a flat level-1 query
        // over the container — the reference-operating replacement for
        // the retired next_page tree-walk.
        children <- QueryToolOutputTool.invoke(QueryToolOutputInput(callId = callId.value, level = Some(1)),
                                               toolContext(ctx, sigil.event.Event.id(), QueryToolOutputTool.name))
      } yield {
        children.items.size shouldBe 2  // two ALPHA matches in a.scala
        val lineMatches = children.items.map(_.as[GrepNode]).collect { case l: GrepNode.LineMatch => l }
        lineMatches.map(_.lineNumber).toSet shouldBe Set(1, 3)
      }
    }
  }

  "GlobTool (paginated, flat)" should {
    "list files in pages" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx    = turnContext()
      for {
        _   <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "x"), ctx, sigil.event.Event.id()).toList
        _   <- new WriteFileTool(fs).execute(WriteFileInput("b.scala", "x"), ctx, sigil.event.Event.id()).toList
        _   <- new WriteFileTool(fs).execute(WriteFileInput("c.txt",   "x"), ctx, sigil.event.Event.id()).toList
        out <- new GlobTool(fs).execute(GlobInput(basePath = ".", pattern = "*.scala"), ctx, callId).toList
      } yield {
        val page    = firstPage(out)
        val entries = page.items.map(_.as[GlobEntry].path)
        entries.toSet shouldBe Set("a.scala", "b.scala")
        page.hasMore shouldBe false
      }
    }
  }

  "BashTool (paginated lines)" should {
    "emit stdout lines followed by an Exit row" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx    = turnContext()
      for {
        out <- new BashTool(fs).execute(BashInput("printf 'one\\ntwo\\nthree'"), ctx, callId).toList
      } yield {
        val page  = firstPage(out)
        val lines = page.items.map(_.as[BashLine])
        // Exactly three Stdout rows + one Exit row.
        lines.count {
          case _: BashLine.Stdout => true
          case _                  => false
        } shouldBe 3
        lines.collect { case e: BashLine.Exit => e.code } shouldBe List(0)
      }
    }

    "carry non-zero exit codes in the Exit row" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx    = turnContext()
      new BashTool(fs).execute(BashInput("exit 42"), ctx, callId).toList.map { events =>
        val page = firstPage(events)
        page.items.map(_.as[BashLine]).collect { case e: BashLine.Exit => e.code } shouldBe List(42)
      }
    }
  }

  "QueryToolOutputTool (paging past the first batch)" should {
    "read the second page of a large result via query_tool_output(page = 1)" in withTempDir { (fs, _) =>
      val callId = sigil.event.Event.id()
      val ctx    = turnContext()
      // 120 files; firstPageSize is 50 (default) → first page has 50, hasMore = true.
      val writes = (1 to 120).map(i => new WriteFileTool(fs).execute(WriteFileInput(f"f$i%03d.scala", "x"), ctx, sigil.event.Event.id()).toList)
      for {
        _    <- Task.sequence(writes.toList)
        out  <- new GlobTool(fs).execute(GlobInput(basePath = ".", pattern = "*.scala"), ctx, callId).toList
        page  = firstPage(out)
        next <- QueryToolOutputTool.invoke(QueryToolOutputInput(callId = callId.value, level = Some(0), page = 1, pageSize = 50),
                                           toolContext(ctx, sigil.event.Event.id(), QueryToolOutputTool.name))
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
      val ctx    = turnContext()
      for {
        _    <- new WriteFileTool(fs).execute(WriteFileInput("a.scala", "alpha\nbeta\nALPHA"), ctx, sigil.event.Event.id()).toList
        _    <- new WriteFileTool(fs).execute(WriteFileInput("b.scala", "beta\nalpha"), ctx, sigil.event.Event.id()).toList
        _    <- new GrepTool(fs).execute(GrepInput(path = ".", pattern = "(?i)alpha"), ctx, callId).toList
        // Query the call's rows for the one whose payload mentions "a.scala".
        out <- QueryToolOutputTool.invoke(
          QueryToolOutputInput(callId = callId.value, containsText = Some("a.scala"), level = Some(0)),
          toolContext(ctx, sigil.event.Event.id(), QueryToolOutputTool.name)
        )
      } yield {
        out.items.map(_.as[GrepNode]).collect { case f: GrepNode.FileMatch => f.filePath } shouldBe List("a.scala")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
