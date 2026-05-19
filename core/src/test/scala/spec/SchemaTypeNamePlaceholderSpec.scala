package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, ToolOutcome, ToolResults}
import sigil.tool.fs.{EditFileTool, GrepTool, LocalFileSystemContext, ReadFileTool}
import sigil.tool.model.{EditFileInput, GrepInput, ReadFileInput, ResponseContent}

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
      currentToolInvokeId = Some(Event.id())
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

  private def failureMessage(events: List[Event]): Message =
    events.collectFirst {
      case m: Message
        if m.role == MessageRole.Tool &&
          m.disposition.isInstanceOf[MessageDisposition.Failure] => m
    }.getOrElse(fail(s"expected a Tool-role Failure Message; saw: ${events.map(_.getClass.getSimpleName).mkString(", ")}"))

  private def failureText(m: Message): String =
    m.content.collect { case ResponseContent.Text(t) => t }.mkString

  private def failureToolResults(events: List[Event]): ToolResults =
    events.collectFirst {
      case t: ToolResults if t.outcome.isInstanceOf[ToolOutcome.Failure] => t
    }.getOrElse(fail(s"expected a Failure ToolResults; saw: ${events.map(_.getClass.getSimpleName).mkString(", ")}"))

  "edit_file" should {

    "reject `filePath = \"string\"` without touching the filesystem" in withTempDir { dir =>
      val ctx = new LocalFileSystemContext(Some(dir))
      val tool = new EditFileTool(ctx)
      val tc = turnContext()
      tool.execute(EditFileInput(filePath = "string", oldString = "a", newString = "b"), tc).toList.map { events =>
        val msg = failureMessage(events)
        val text = failureText(msg)
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
        tc
      ).toList.map { events =>
        // No Tool-role Failure Message: the edit succeeded.
        events.collectFirst {
          case m: Message
            if m.role == MessageRole.Tool &&
              m.disposition.isInstanceOf[MessageDisposition.Failure] => m
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
      tool.execute(GrepInput(path = ".", pattern = "string"), tc).toList.map { events =>
        // No Failure ToolResults — the search ran.
        events.collectFirst {
          case t: ToolResults if t.outcome.isInstanceOf[ToolOutcome.Failure] => t
        } shouldBe None
        // A successful ToolResults landed.
        events.collectFirst {
          case t: ToolResults if t.outcome == ToolOutcome.Success => t
        } should not be None
      }
    }

    "reject `path = \"string\"` (path is NOT whitelisted)" in withTempDir { dir =>
      val ctx = new LocalFileSystemContext(Some(dir))
      val tool = new GrepTool(ctx)
      val tc = turnContext()
      tool.execute(GrepInput(path = "string", pattern = "real-pattern"), tc).toList.map { events =>
        val tr = failureToolResults(events)
        val reason = tr.outcome match {
          case ToolOutcome.Failure(r, _) => r
          case _                          => ""
        }
        reason should include ("JSON Schema type name")
        reason should include ("path")
      }
    }
  }

  "read_file" should {

    "reject `filePath = \"integer\"` without touching the filesystem" in withTempDir { dir =>
      val ctx = new LocalFileSystemContext(Some(dir))
      val tool = new ReadFileTool(ctx)
      val tc = turnContext()
      tool.execute(ReadFileInput(filePath = "integer"), tc).toList.map { events =>
        val msg = failureMessage(events)
        val text = failureText(msg)
        text should include ("JSON Schema type name")
        text should include ("filePath")
        Files.list(dir).iterator().asScala.toList shouldBe empty
      }
    }
  }
}
