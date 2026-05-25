package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.{Event, ToolOutcome}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.fs.{EditFileTool, GrepTool, LocalFileSystemContext, ReadFileTool}
import sigil.tool.model.{EditFileInput, GrepInput, ReadFileInput}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/**
 * Acceptance for `PlaceholderInputDetector` — rejects literal JSON-
 * Schema type-name tokens ("string", etc.) on non-whitelisted string
 * input fields. See `sigil.tool.PlaceholderInputDetector` for the
 * detection rules; the user-supplied-content whitelist (`oldString`,
 * `newString`, `pattern`, `query`, `text`, `content`, `newText`) lets
 * legitimate single-word values like `"string"` pass through when
 * they're semantically search / replacement payloads.
 */
class SchemaTypeNamePlaceholderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("schema-placeholder-spec-conv")

  private def turnContext(): TurnContext = {
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id    = convId
    )
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser),
      conversation = conv,
      turnInput    = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  private def withTempDir[T](body: Path => Task[T]): Task[T] = Task.defer {
    val dir = Files.createTempDirectory("sigil-placeholder-")
    body(dir).guarantee(Task {
      if (Files.exists(dir)) {
        val s = Files.walk(dir)
        try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
        finally s.close()
      }
    })
  }

  private def failureText(signals: List[Signal]): String =
    signals.collectFirst {
      case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) =>
        d.summary.getOrElse(d.outcome.collect { case ToolOutcome.Failure(r, _) => r }.getOrElse(""))
    }.getOrElse(fail(s"expected a settling ToolDelta carrying ToolOutcome.Failure; saw: ${signals.map(_.getClass.getSimpleName).mkString(", ")}"))

  "edit_file" should {

    "reject `filePath = \"string\"` without touching the filesystem" in withTempDir { dir =>
      val ctx = new LocalFileSystemContext(Some(dir))
      val tool = new EditFileTool(ctx)
      val tc = turnContext()
      tool.execute(EditFileInput(filePath = "string", oldString = "a", newString = "b"), tc, Event.id()).toList.map { signals =>
        val text = failureText(signals)
        text should include ("JSON Schema type name")
        text should include ("filePath")
        // The tool never touched the filesystem — no temp-dir contents
        // mutated, no "file not found" diagnostic from the read path.
        Files.list(dir).iterator().asScala.toList shouldBe empty
      }
    }

    "pass through when `filePath` is real and `oldString`/`newString` are the literal word \"string\"" in withTempDir { dir =>
      val target = dir.resolve("notes.txt")
      Files.writeString(target, "the value is string today", StandardOpenOption.CREATE)

      val ctx = new LocalFileSystemContext(Some(dir))
      val tool = new EditFileTool(ctx)
      val tc = turnContext()
      tool.execute(
        EditFileInput(
          filePath  = "notes.txt",
          oldString = "string",
          newString = "String"
        ),
        tc, Event.id()
      ).toList.map { signals =>
        // No failure-outcome ToolDelta: the edit succeeded.
        signals.collectFirst {
          case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) => d
        } shouldBe None
        // File now contains the replacement.
        Files.readString(target) should include ("String")
      }
    }
  }

  "grep" should {

    "NOT reject `pattern = \"string\"` (pattern is whitelisted as user-supplied content)" in withTempDir { dir =>
      val src = dir.resolve("a.txt")
      Files.writeString(src, "the word string appears here\nstring again\nno match here\n",
        StandardOpenOption.CREATE)

      val ctx = new LocalFileSystemContext(Some(dir))
      val tool = new GrepTool(ctx)
      val tc = turnContext()
      tool.execute(GrepInput(path = ".", pattern = "string"), tc, Event.id()).toList.map { signals =>
        // No Failure outcome — the search ran.
        signals.collectFirst {
          case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) => d
        } shouldBe None
        // A successful settling ToolDelta landed.
        signals.collectFirst {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        } should not be None
      }
    }

    "reject `path = \"string\"` (path is NOT whitelisted)" in withTempDir { dir =>
      val ctx = new LocalFileSystemContext(Some(dir))
      val tool = new GrepTool(ctx)
      val tc = turnContext()
      tool.execute(GrepInput(path = "string", pattern = "real-pattern"), tc, Event.id()).toList.map { signals =>
        val text = failureText(signals)
        text should include ("JSON Schema type name")
        text should include ("path")
      }
    }
  }

  "read_file" should {

    "reject `filePath = \"integer\"` without touching the filesystem" in withTempDir { dir =>
      val ctx = new LocalFileSystemContext(Some(dir))
      val tool = new ReadFileTool(ctx)
      val tc = turnContext()
      tool.execute(ReadFileInput(filePath = "integer"), tc, Event.id()).toList.map { signals =>
        val text = failureText(signals)
        text should include ("JSON Schema type name")
        text should include ("filePath")
        Files.list(dir).iterator().asScala.toList shouldBe empty
      }
    }
  }
}
