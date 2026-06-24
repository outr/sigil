package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.{Event, ToolOutcome}
import sigil.signal.{Signal, ToolDelta}
import sigil.storage.FileVersion
import sigil.tool.fs.{EditAtRangeTool, EditFileTool, ExpectedHash, LocalFileSystemContext}
import sigil.tool.model.{EditAtRangeInput, EditAtRangeOutput, EditFileInput, EditFileOutput}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Sigil #402 — `expectedHash` is `Option[String]`, but a model writing "no
 * hash" sometimes serializes the sentinel string `"None"` / `"null"` / `""`,
 * which arrives as a non-empty `Some` and is treated as a real expected hash.
 * Since the sentinel never matches the file's real hash, every such edit fails
 * `Stale`. The tools must normalize the sentinel to absent (unconditional edit),
 * while a real hash still drives the genuine concurrency guard.
 */
class ExpectedHashSentinelSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def turnContext(convId: Id[Conversation]): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )

  private def withWorkspace[A](body: (LocalFileSystemContext, TurnContext) => Task[A]): Task[A] = Task.defer {
    val dir = Files.createTempDirectory("sigil-402-")
    val convId = Conversation.id(s"wf402-${rapid.Unique()}")
    val fs = new LocalFileSystemContext(Some(dir))
    body(fs, turnContext(convId)).guarantee(Task {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def isStale(signals: List[Signal]): Boolean =
    signals.exists {
      case d: ToolDelta => d.outcome.exists {
        case f: ToolOutcome.Failure => f.reason.contains("file changed since")
        case _                      => false
      }
      case _ => false
    }

  private def editFileSucceeded(signals: List[Signal]): Boolean =
    signals.exists { case d: ToolDelta => d.output.exists(_.isInstanceOf[EditFileOutput.Success]); case _ => false }

  private def editRangeSucceeded(signals: List[Signal]): Boolean =
    signals.exists { case d: ToolDelta => d.output.exists(_.isInstanceOf[EditAtRangeOutput.Success]); case _ => false }

  "ExpectedHash.normalize (#402)" should {
    "drop the no-value sentinels and empties" in Task {
      ExpectedHash.normalize(Some("None")) shouldBe None
      ExpectedHash.normalize(Some("none")) shouldBe None
      ExpectedHash.normalize(Some("null")) shouldBe None
      ExpectedHash.normalize(Some("")) shouldBe None
      ExpectedHash.normalize(Some("   ")) shouldBe None
      ExpectedHash.normalize(None) shouldBe None
    }
    "keep a real hash (trimmed)" in Task {
      ExpectedHash.normalize(Some("  abc123def  ")) shouldBe Some("abc123def")
    }
  }

  "edit_file with a sentinel expectedHash (#402)" should {
    "commit (Success) for Some(\"None\") on an unmodified file" in withWorkspace { (fs, tc) =>
      for {
        _ <- fs.writeFile("a.txt", "foo\n")
        r <- new EditFileTool(fs).execute(
          EditFileInput("a.txt", oldString = "foo", newString = "bar", expectedHash = Some("None")), tc, Event.id()).toList
        onDisk <- fs.readFile("a.txt")
      } yield {
        isStale(r) shouldBe false
        editFileSucceeded(r) shouldBe true
        onDisk shouldBe "bar\n"
      }
    }
    "commit for Some(\"null\") and Some(\"  \") too" in withWorkspace { (fs, tc) =>
      for {
        _  <- fs.writeFile("b.txt", "one\ntwo\n")
        r1 <- new EditFileTool(fs).execute(
          EditFileInput("b.txt", oldString = "one", newString = "1", expectedHash = Some("null")), tc, Event.id()).toList
        r2 <- new EditFileTool(fs).execute(
          EditFileInput("b.txt", oldString = "two", newString = "2", expectedHash = Some("  ")), tc, Event.id()).toList
        onDisk <- fs.readFile("b.txt")
      } yield {
        editFileSucceeded(r1) shouldBe true
        editFileSucceeded(r2) shouldBe true
        onDisk shouldBe "1\n2\n"
      }
    }
  }

  "edit_file with a REAL expectedHash (#402 — guard unchanged)" should {
    "commit when the hash is current, and return Stale when it isn't" in withWorkspace { (fs, tc) =>
      val content = "alpha\n"
      val currentHash = FileVersion.hashOf(content)
      for {
        _      <- fs.writeFile("c.txt", content)
        good   <- new EditFileTool(fs).execute(
          EditFileInput("c.txt", oldString = "alpha", newString = "beta", expectedHash = Some(currentHash)), tc, Event.id()).toList
        // The file is now "beta\n"; the original hash is stale.
        stale  <- new EditFileTool(fs).execute(
          EditFileInput("c.txt", oldString = "beta", newString = "gamma", expectedHash = Some(currentHash)), tc, Event.id()).toList
      } yield {
        editFileSucceeded(good) shouldBe true
        isStale(stale) shouldBe true
      }
    }
  }

  "edit_at_range with a sentinel expectedHash (#402)" should {
    "commit (Success) for Some(\"None\")" in withWorkspace { (fs, tc) =>
      for {
        _ <- fs.writeFile("r.txt", "l0\nl1\nl2\n")
        r <- new EditAtRangeTool(fs).execute(
          EditAtRangeInput("r.txt", startLine = 1, startChar = 0, endLine = 2, endChar = 0,
            newText = "X\n", expectedHash = Some("None")), tc, Event.id()).toList
        onDisk <- fs.readFile("r.txt")
      } yield {
        isStale(r) shouldBe false
        editRangeSucceeded(r) shouldBe true
        onDisk shouldBe "l0\nX\nl2\n"
      }
    }
    "still return Stale for a genuinely stale REAL hash" in withWorkspace { (fs, tc) =>
      val content = "l0\nl1\nl2\n"
      val staleHash = FileVersion.hashOf(content)
      for {
        _ <- fs.writeFile("r2.txt", content)
        _ <- fs.writeFile("r2.txt", "l0\nCHANGED\nl2\n") // external write — hash now stale
        r <- new EditAtRangeTool(fs).execute(
          EditAtRangeInput("r2.txt", startLine = 0, startChar = 0, endLine = 1, endChar = 0,
            newText = "Y\n", expectedHash = Some(staleHash)), tc, Event.id()).toList
      } yield {
        isStale(r) shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
