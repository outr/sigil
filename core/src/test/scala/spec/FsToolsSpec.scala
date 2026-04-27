package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Message
import sigil.tool.fs.{BashTool, DeleteFileTool, EditFileTool, FileSystemContext, GlobTool, GrepTool, LocalFileSystemContext, ReadFileTool, WriteFileTool}
import sigil.tool.model.{BashInput, DeleteFileInput, EditFileInput, GlobInput, GrepInput, ReadFileInput, ResponseContent, WriteFileInput}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * End-to-end coverage for the `sigil.tool.fs` family. Each test
 * spins up a fresh temp directory as the [[LocalFileSystemContext]]
 * sandbox, exercises one tool, and parses the emitted JSON from
 * the result Message.
 */
class FsToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId  = Conversation.id("fs-tools-conv")
  private val topicId = TestTopicId

  private def withTempDir[T](body: (FileSystemContext, Path) => Task[T]): Task[T] = Task.defer {
    val dir = Files.createTempDirectory("sigil-fs-tools-")
    val ctx = new LocalFileSystemContext(Some(dir))
    body(ctx, dir).guarantee(Task {
      // Best-effort cleanup
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def turnContext(): TurnContext = {
    val conv = Conversation(
      topics = List(TopicEntry(topicId, "test", "test")),
      _id    = convId
    )
    TurnContext(
      sigil            = TestSigil,
      chain            = List(TestUser),
      conversation    = conv,
      conversationView = ConversationView(conversationId = convId),
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  private def extractJson(events: List[sigil.event.Event]): fabric.Json = {
    events.collectFirst { case m: Message =>
      m.content.collectFirst { case ResponseContent.Text(t) => t }
    }.flatten.map(JsonParser(_)).getOrElse(fabric.Obj.empty)
  }

  "WriteFileTool + ReadFileTool" should {
    "round-trip a file's contents" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        wrote <- new WriteFileTool(ctx).execute(WriteFileInput("notes.txt", "hello sigil"), tc).toList
        read  <- new ReadFileTool(ctx).execute(ReadFileInput("notes.txt"), tc).toList
      } yield {
        extractJson(wrote).get("success").map(_.asBoolean) shouldBe Some(true)
        extractJson(read).get("content").map(_.asString) shouldBe Some("hello sigil")
      }
    }
  }

  "ReadFileTool" should {
    "read a window via offset and limit" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      val text = (1 to 10).map(i => s"line $i").mkString("\n")
      for {
        _    <- new WriteFileTool(ctx).execute(WriteFileInput("data.log", text), tc).toList
        read <- new ReadFileTool(ctx).execute(ReadFileInput("data.log", offset = Some(2), limit = Some(3)), tc).toList
      } yield {
        val payload = extractJson(read)
        payload.get("content").map(_.asString) shouldBe Some("line 3\nline 4\nline 5")
        payload.get("totalLines").map(_.asInt) shouldBe Some(10)
        payload.get("linesRead").map(_.asInt) shouldBe Some(3)
      }
    }
  }

  "EditFileTool" should {
    "replace a unique substring" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _      <- new WriteFileTool(ctx).execute(WriteFileInput("c.toml", "x = 1\ny = 2"), tc).toList
        edited <- new EditFileTool(ctx).execute(EditFileInput("c.toml", "y = 2", "y = 99"), tc).toList
        re     <- new ReadFileTool(ctx).execute(ReadFileInput("c.toml"), tc).toList
      } yield {
        extractJson(edited).get("success").map(_.asBoolean) shouldBe Some(true)
        extractJson(edited).get("replacements").map(_.asInt) shouldBe Some(1)
        extractJson(re).get("content").map(_.asString) shouldBe Some("x = 1\ny = 99")
      }
    }

    "reject ambiguous edits without replaceAll" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _      <- new WriteFileTool(ctx).execute(WriteFileInput("d.txt", "foo\nfoo"), tc).toList
        edited <- new EditFileTool(ctx).execute(EditFileInput("d.txt", "foo", "bar"), tc).toList
      } yield {
        extractJson(edited).get("success").map(_.asBoolean) shouldBe Some(false)
        extractJson(edited).get("error").map(_.asString.contains("not unique")).getOrElse(false) shouldBe true
      }
    }
  }

  "DeleteFileTool" should {
    "delete an existing file and report deleted = true" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("scratch.txt", "x"), tc).toList
        d <- new DeleteFileTool(ctx).execute(DeleteFileInput("scratch.txt"), tc).toList
      } yield {
        extractJson(d).get("deleted").map(_.asBoolean) shouldBe Some(true)
      }
    }
  }

  "GlobTool" should {
    "list files matching a pattern" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _    <- new WriteFileTool(ctx).execute(WriteFileInput("a.scala", "x"), tc).toList
        _    <- new WriteFileTool(ctx).execute(WriteFileInput("b.scala", "x"), tc).toList
        _    <- new WriteFileTool(ctx).execute(WriteFileInput("c.txt", "x"), tc).toList
        out  <- new GlobTool(ctx).execute(GlobInput(basePath = ".", pattern = "*.scala"), tc).toList
      } yield {
        val paths = extractJson(out).get("paths").map(_.asVector.map(_.asString).toList).getOrElse(Nil).toSet
        paths shouldBe Set("a.scala", "b.scala")
      }
    }
  }

  "GrepTool" should {
    "find regex matches with line numbers" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _    <- new WriteFileTool(ctx).execute(WriteFileInput("notes.md", "alpha\nbeta\nALPHA"), tc).toList
        out  <- new GrepTool(ctx).execute(GrepInput(path = ".", pattern = "(?i)alpha"), tc).toList
      } yield {
        val matches = extractJson(out).get("matches").map(_.asVector.toList).getOrElse(Nil)
        matches.map(_.get("lineNumber").map(_.asInt)).toSet shouldBe Set(Some(1), Some(3))
      }
    }
  }

  "BashTool" should {
    "execute a shell command and capture stdout" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      new BashTool(ctx).execute(BashInput("echo sigil-bash"), tc).toList.map { events =>
        val payload = extractJson(events)
        payload.get("stdout").map(_.asString.trim) shouldBe Some("sigil-bash")
        payload.get("exitCode").map(_.asInt) shouldBe Some(0)
      }
    }

    "report a non-zero exit code" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      new BashTool(ctx).execute(BashInput("exit 42"), tc).toList.map { events =>
        extractJson(events).get("exitCode").map(_.asInt) shouldBe Some(42)
      }
    }
  }
}
