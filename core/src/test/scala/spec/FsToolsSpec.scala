package spec

import fabric.io.JsonParser
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.{Message, ToolResults}
import sigil.tool.fs.{DeleteFileTool, EditFileTool, FileSystemContext, LocalFileSystemContext, ReadFileTool, WriteFileTool}
import sigil.tool.model.{DeleteFileInput, DeleteFileOutput, EditFileInput, EditFileOutput, ReadFileInput, ReadFileOutput, ResponseContent, WriteFileInput, WriteFileOutput}

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
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  private def extractJson(events: List[sigil.event.Event]): fabric.Json = {
    // Prefer the typed payload from a ToolResults (the new
    // TypedOutputTool emission path); fall back to a Tool-role
    // Message's Text content for legacy untyped tools.
    val fromTypedResults = events.collectFirst {
      case t: ToolResults if t.typed.isDefined => t.typed.get
    }
    fromTypedResults.orElse {
      events.collectFirst { case m: Message =>
        m.content.collectFirst { case ResponseContent.Text(t) => t }
      }.flatten.map(JsonParser(_))
    }.getOrElse(fabric.Obj.empty)
  }

  /** Decode the typed payload of a ToolResults event back to the
    * tool's Output case class via its registered RW — what apps
    * doing tool-to-tool composition do via [[Tool.invoke]]. */
  private def typed[T](events: List[sigil.event.Event])(using rw: RW[T]): T =
    rw.write(extractJson(events))

  "WriteFileTool + ReadFileTool" should {
    "round-trip a file's contents" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        wrote <- new WriteFileTool(ctx).execute(WriteFileInput("notes.txt", "hello sigil"), tc).toList
        read  <- new ReadFileTool(ctx).execute(ReadFileInput("notes.txt"), tc).toList
      } yield {
        typed[WriteFileOutput](wrote) shouldBe a[WriteFileOutput.Success]
        typed[ReadFileOutput](read).content shouldBe "hello sigil"
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
        val payload = typed[ReadFileOutput](read)
        payload.content shouldBe "line 3\nline 4\nline 5"
        payload.totalLines shouldBe 10
        payload.linesRead shouldBe 3
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
        typed[EditFileOutput](edited) match {
          case EditFileOutput.Success(replacements, _) => replacements shouldBe 1
          case other => fail(s"expected Success, got $other")
        }
        typed[ReadFileOutput](re).content shouldBe "x = 1\ny = 99"
      }
    }

    "reject ambiguous edits without replaceAll" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _      <- new WriteFileTool(ctx).execute(WriteFileInput("d.txt", "foo\nfoo"), tc).toList
        edited <- new EditFileTool(ctx).execute(EditFileInput("d.txt", "foo", "bar"), tc).toList
      } yield {
        typed[EditFileOutput](edited) match {
          case EditFileOutput.NotUnique(occ) => occ shouldBe 2
          case other                         => fail(s"expected NotUnique, got $other")
        }
      }
    }

    "commit safe-edit when expectedHash matches and surface fresh hash" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _      <- new WriteFileTool(ctx).execute(WriteFileInput("safe.toml", "x = 1\ny = 2"), tc).toList
        readJ  <- new ReadFileTool(ctx).execute(ReadFileInput("safe.toml"), tc).toList
        hash    = typed[ReadFileOutput](readJ).hash.get
        edited <- new EditFileTool(ctx).execute(
                    EditFileInput("safe.toml", "y = 2", "y = 99", expectedHash = Some(hash)),
                    tc
                  ).toList
        re     <- new ReadFileTool(ctx).execute(ReadFileInput("safe.toml"), tc).toList
      } yield {
        typed[EditFileOutput](edited) match {
          case EditFileOutput.Success(repls, h) =>
            repls shouldBe 1
            h shouldBe defined
          case other => fail(s"expected Success, got $other")
        }
        typed[ReadFileOutput](re).content shouldBe "x = 1\ny = 99"
      }
    }

    "surface stale on safe-edit when expectedHash is wrong, leaving file unchanged" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _      <- new WriteFileTool(ctx).execute(WriteFileInput("conflict.toml", "x = 1"), tc).toList
        edited <- new EditFileTool(ctx).execute(
                    EditFileInput("conflict.toml", "x = 1", "x = 2", expectedHash = Some("not-the-real-hash")),
                    tc
                  ).toList
        re     <- new ReadFileTool(ctx).execute(ReadFileInput("conflict.toml"), tc).toList
      } yield {
        typed[EditFileOutput](edited) match {
          case EditFileOutput.Stale(currentHash, currentContent) =>
            currentHash should not be empty
            currentContent shouldBe "x = 1"
          case other => fail(s"expected Stale, got $other")
        }
        // File unchanged
        typed[ReadFileOutput](re).content shouldBe "x = 1"
      }
    }

    "WriteFileTool surfaces written/stale results when expectedHash is supplied" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _      <- new WriteFileTool(ctx).execute(WriteFileInput("ow.txt", "v1"), tc).toList
        readJ  <- new ReadFileTool(ctx).execute(ReadFileInput("ow.txt"), tc).toList
        hash    = typed[ReadFileOutput](readJ).hash.get
        ok     <- new WriteFileTool(ctx).execute(
                    WriteFileInput("ow.txt", "v2", expectedHash = Some(hash)),
                    tc
                  ).toList
        // Now hash is stale — try writing again with the OLD hash.
        stale  <- new WriteFileTool(ctx).execute(
                    WriteFileInput("ow.txt", "v3", expectedHash = Some(hash)),
                    tc
                  ).toList
      } yield {
        typed[WriteFileOutput](ok) shouldBe a[WriteFileOutput.Success]
        typed[WriteFileOutput](stale) match {
          case WriteFileOutput.Stale(_, content) => content shouldBe "v2"
          case other                             => fail(s"expected Stale, got $other")
        }
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
        typed[DeleteFileOutput](d).deleted shouldBe true
      }
    }
  }

  // Grep / Glob / Bash now stream into the paginated tool-output
  // collection; their end-to-end coverage moved to
  // [[PaginatedToolsSpec]].

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
