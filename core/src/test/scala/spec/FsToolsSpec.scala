package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.ToolOutcome
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.fs.{DeleteFileTool, EditFileTool, FileSystemContext, LocalFileSystemContext, ReadFileTool, WriteFileTool}
import sigil.tool.model.{
  DeleteFileInput, DeleteFileOutput, EditFileInput, EditFileOutput, ReadFileInput, ReadFileOutput, WriteFileInput, WriteFileOutput
}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import sigil.event.Event

/**
 * End-to-end coverage for the `sigil.tool.fs` family. Each test
 * spins up a fresh temp directory as the [[LocalFileSystemContext]]
 * sandbox, exercises one tool, and parses the emitted JSON from
 * the result Message.
 */
class FsToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("fs-tools-conv")
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
      _id = convId
    )
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  /**
   * Recover the typed payload from the settling [[ToolDelta]]'s
   * `output` — the same concrete instance the tool produced, no
   * JSON round-trip.
   */
  private def typed[T <: sigil.tool.ToolOutput](signals: List[Signal])(using ct: ClassTag[T]): T =
    signals.collectFirst {
      case d: ToolDelta if d.output.exists(o => ct.runtimeClass.isInstance(o)) =>
        d.output.get.asInstanceOf[T]
    }.getOrElse(fail(
      s"expected ToolOutput of type ${ct.runtimeClass.getSimpleName}; saw outputs: " +
        signals.collect { case d: ToolDelta => d.output.map(_.getClass.getSimpleName) }.mkString(", ")
    ))

  /**
   * Extract the failure body from the settling ToolDelta — the text
   * the agent reads when a tool resolved a `ToolResult.Failure`.
   */
  private def failureText(signals: List[Signal]): String =
    signals.collectFirst {
      case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) =>
        d.summary.getOrElse(d.outcome.collect { case ToolOutcome.Failure(r, _) => r }.getOrElse(""))
    }.getOrElse(fail(
      s"expected a settling ToolDelta carrying ToolOutcome.Failure; saw: ${signals.map(_.getClass.getSimpleName).mkString(", ")}"))

  "WriteFileTool + ReadFileTool" should {
    "round-trip a file's contents" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        wrote <- new WriteFileTool(ctx).execute(WriteFileInput("notes.txt", "hello sigil"), tc, Event.id()).toList
        read <- new ReadFileTool(ctx).execute(ReadFileInput("notes.txt"), tc, Event.id()).toList
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
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("data.log", text), tc, Event.id()).toList
        read <- new ReadFileTool(ctx).execute(ReadFileInput("data.log", offset = Some(2), limit = Some(3)), tc, Event.id()).toList
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
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("c.toml", "x = 1\ny = 2"), tc, Event.id()).toList
        edited <- new EditFileTool(ctx).execute(EditFileInput("c.toml", "y = 2", "y = 99"), tc, Event.id()).toList
        re <- new ReadFileTool(ctx).execute(ReadFileInput("c.toml"), tc, Event.id()).toList
      } yield {
        typed[EditFileOutput](edited) match {
          case EditFileOutput.Success(replacements, _) => replacements shouldBe 1
          case other => fail(s"expected Success, got $other")
        }
        typed[ReadFileOutput](re).content shouldBe "x = 1\ny = 99"
      }
    }

    "reject ambiguous edits without replaceAll as a typed Failure (file unchanged)" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("d.txt", "foo\nfoo"), tc, Event.id()).toList
        edited <- new EditFileTool(ctx).execute(EditFileInput("d.txt", "foo", "bar"), tc, Event.id()).toList
        re <- new ReadFileTool(ctx).execute(ReadFileInput("d.txt"), tc, Event.id()).toList
      } yield {
        val text = failureText(edited)
        text should include("matched 2 times")
        text should include("replaceAll: true")
        typed[ReadFileOutput](re).content shouldBe "foo\nfoo"
      }
    }

    "surface a typed Failure when oldString doesn't match (file unchanged, bug #183)" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("nm.txt", "abcdef"), tc, Event.id()).toList
        edited <- new EditFileTool(ctx).execute(EditFileInput("nm.txt", "xyz", "ZZZ"), tc, Event.id()).toList
        re <- new ReadFileTool(ctx).execute(ReadFileInput("nm.txt"), tc, Event.id()).toList
      } yield {
        val text = failureText(edited)
        text should include("no match for `oldString`")
        text should include("Read the file again")
        // File on disk is unchanged.
        typed[ReadFileOutput](re).content shouldBe "abcdef"
      }
    }

    "commit safe-edit when expectedHash matches and surface fresh hash" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("safe.toml", "x = 1\ny = 2"), tc, Event.id()).toList
        readJ <- new ReadFileTool(ctx).execute(ReadFileInput("safe.toml"), tc, Event.id()).toList
        hash = typed[ReadFileOutput](readJ).hash.get
        edited <- new EditFileTool(ctx).execute(
          EditFileInput("safe.toml", "y = 2", "y = 99", expectedHash = Some(hash)),
          tc,
          Event.id()
        ).toList
        re <- new ReadFileTool(ctx).execute(ReadFileInput("safe.toml"), tc, Event.id()).toList
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

    "surface stale on safe-edit when expectedHash is wrong as a typed Failure (file unchanged)" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("conflict.toml", "x = 1"), tc, Event.id()).toList
        edited <- new EditFileTool(ctx).execute(
          EditFileInput("conflict.toml", "x = 1", "x = 2", expectedHash = Some("not-the-real-hash")),
          tc,
          Event.id()
        ).toList
        re <- new ReadFileTool(ctx).execute(ReadFileInput("conflict.toml"), tc, Event.id()).toList
      } yield {
        val text = failureText(edited)
        text should include("file changed since")
        text should include("Re-read the file")
        // File unchanged
        typed[ReadFileOutput](re).content shouldBe "x = 1"
      }
    }

    "WriteFileTool surfaces written/stale results when expectedHash is supplied" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("ow.txt", "v1"), tc, Event.id()).toList
        readJ <- new ReadFileTool(ctx).execute(ReadFileInput("ow.txt"), tc, Event.id()).toList
        hash = typed[ReadFileOutput](readJ).hash.get
        ok <- new WriteFileTool(ctx).execute(
          WriteFileInput("ow.txt", "v2", expectedHash = Some(hash)),
          tc,
          Event.id()
        ).toList
        // Now hash is stale — try writing again with the OLD hash.
        stale <- new WriteFileTool(ctx).execute(
          WriteFileInput("ow.txt", "v3", expectedHash = Some(hash)),
          tc,
          Event.id()
        ).toList
      } yield {
        typed[WriteFileOutput](ok) shouldBe a[WriteFileOutput.Success]
        // A stale write (old expectedHash) is now a recoverable Failure
        // the agent reads, not a Success-shaped Stale payload.
        failureText(stale) should include("file changed since")
      }
    }
  }

  "DeleteFileTool" should {
    "delete an existing file and report deleted = true" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _ <- new WriteFileTool(ctx).execute(WriteFileInput("scratch.txt", "x"), tc, Event.id()).toList
        d <- new DeleteFileTool(ctx).execute(DeleteFileInput("scratch.txt"), tc, Event.id()).toList
      } yield typed[DeleteFileOutput](d).deleted shouldBe true
    }
  }

  // Grep / Glob / Bash now stream into the paginated tool-output
  // collection; their end-to-end coverage moved to
  // [[PaginatedToolsSpec]].

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
