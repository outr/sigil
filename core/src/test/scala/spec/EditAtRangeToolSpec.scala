package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.tool.fs.{EditAtRangeTool, LocalFileSystemContext, ReadFileTool, WriteFileTool}
import sigil.tool.model.{EditAtRangeInput, ReadFileInput, ReadFileOutput, ResponseContent, WriteFileInput}

import java.nio.file.{Files, Path}

class EditAtRangeToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def withTempDir(f: (sigil.tool.fs.FileSystemContext, Path) => Task[org.scalatest.compatible.Assertion]): Task[org.scalatest.compatible.Assertion] = {
    val tmp = Files.createTempDirectory("edit-at-range-")
    val ctx = LocalFileSystemContext(basePath = Some(tmp))
    f(ctx, tmp)
  }

  private def turnContext() =
    sigil.TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser),
      conversation = sigil.conversation.Conversation(topics = TestTopicStack),
      turnInput    = sigil.conversation.TurnInput(conversationId = sigil.conversation.Conversation.id("editrange"))
    )

  private def edit(ctx: sigil.tool.fs.FileSystemContext, in: EditAtRangeInput) =
    new EditAtRangeTool(ctx).execute(in, turnContext()).toList

  "EditAtRangeTool — pure applyRange" should {

    "replace a single-line range" in {
      val content = "alpha\nbeta\ngamma\n"
      val in = EditAtRangeInput("f", 1, 0, 1, 4, "BEETA")
      EditAtRangeTool.applyRange(content, in) match {
        case Right(next) => Task.pure(next shouldBe "alpha\nBEETA\ngamma\n")
        case Left(err)   => Task.pure(fail(err))
      }
    }

    "insert at a position when start == end" in {
      val content = "alpha\nbeta\n"
      val in = EditAtRangeInput("f", 1, 0, 1, 0, "PREFIX-")
      EditAtRangeTool.applyRange(content, in) match {
        case Right(next) => Task.pure(next shouldBe "alpha\nPREFIX-beta\n")
        case Left(err)   => Task.pure(fail(err))
      }
    }

    "delete a multi-line span when newText is empty" in {
      val content = "alpha\nbeta\ngamma\ndelta\n"
      val in = EditAtRangeInput("f", 1, 0, 3, 0, "")
      EditAtRangeTool.applyRange(content, in) match {
        case Right(next) => Task.pure(next shouldBe "alpha\ndelta\n")
        case Left(err)   => Task.pure(fail(err))
      }
    }

    "reject end position preceding start" in {
      val in = EditAtRangeInput("f", 2, 5, 1, 0, "x")
      EditAtRangeTool.applyRange("alpha\nbeta\ngamma\n", in) match {
        case Left(msg)   => Task.pure(msg should include ("precedes start"))
        case Right(next) => Task.pure(fail(s"expected Left; got $next"))
      }
    }

    "reject out-of-bounds line index" in {
      val in = EditAtRangeInput("f", 99, 0, 99, 0, "x")
      EditAtRangeTool.applyRange("alpha\nbeta\n", in) match {
        case Left(msg)   => Task.pure(msg should include ("past EOF"))
        case Right(next) => Task.pure(fail(s"expected Left; got $next"))
      }
    }

    "reject character index past line length" in {
      val in = EditAtRangeInput("f", 0, 100, 0, 100, "x")
      EditAtRangeTool.applyRange("short\n", in) match {
        case Left(msg)   => Task.pure(msg should include ("exceeds line"))
        case Right(next) => Task.pure(fail(s"expected Left; got $next"))
      }
    }
  }

  "EditAtRangeTool — end-to-end via FileSystemContext" should {

    "commit a position-based replace and persist the new content on disk" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _      <- new WriteFileTool(ctx).execute(WriteFileInput("a.txt", "hello\nworld\n"), tc).toList
        events <- edit(ctx, EditAtRangeInput("a.txt", 0, 0, 0, 5, "HELLO"))
        re     <- new ReadFileTool(ctx).execute(ReadFileInput("a.txt"), tc).toList
      } yield {
        val results = events.collect { case tr: sigil.event.ToolResults => tr }
        results should not be empty
        // File on disk now contains the replacement.
        re.collectFirst { case tr: sigil.event.ToolResults => tr.typed }.flatten
          .map(j => summon[fabric.rw.RW[ReadFileOutput]].write(j).content) shouldBe Some("HELLO\nworld\n")
      }
    }

    "surface a typed Failure when the range is out of bounds (file unchanged)" in withTempDir { (ctx, _) =>
      val tc = turnContext()
      for {
        _      <- new WriteFileTool(ctx).execute(WriteFileInput("oob.txt", "short\n"), tc).toList
        events <- edit(ctx, EditAtRangeInput("oob.txt", 0, 100, 0, 100, "x"))
        re     <- new ReadFileTool(ctx).execute(ReadFileInput("oob.txt"), tc).toList
      } yield {
        val failureMsg = events.collectFirst {
          case m: sigil.event.Message
            if m.role == sigil.event.MessageRole.Tool &&
              m.disposition.isInstanceOf[sigil.event.MessageDisposition.Failure] => m
        }
        failureMsg should not be empty
        val text = failureMsg.get.content.collect { case ResponseContent.Text(t) => t }.mkString
        text should include ("exceeds line")
        // File on disk is unchanged.
        re.collectFirst { case tr: sigil.event.ToolResults => tr.typed }.flatten
          .map(j => summon[fabric.rw.RW[ReadFileOutput]].write(j).content) shouldBe Some("short\n")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
