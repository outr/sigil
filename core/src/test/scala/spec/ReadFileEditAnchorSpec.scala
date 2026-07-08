package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ContextFrame, Conversation, ConversationView, FrameBuilder, ToolCallState, Topic, TopicEntry, TurnInput}
import sigil.event.{Event, ToolInvoke, ToolOutcome}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.ToolName
import sigil.tool.fs.{EditFileTool, LocalFileSystemContext, ReadFileTool, WriteFileTool}
import sigil.tool.model.{EditFileInput, EditFileOutput, ReadFileInput, ReadFileOutput, WriteFileInput}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Sigil #404 end-to-end — a line copied out of `read_file`'s MODEL-FACING
 * render (the exact string the agent reads, via [[FrameBuilder.computeFrame]])
 * must work verbatim as an `edit_file` `oldString` anchor. Pre-fix the render
 * JSON-escaped the file text (`"` → `\"`, `/` → `\/`), so the copied anchor
 * didn't match the real file and `edit_file` returned `no match`.
 */
class ReadFileEditAnchorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def withWorkspace[A](body: (LocalFileSystemContext, TurnContext) => Task[A]): Task[A] = Task.defer {
    val dir = Files.createTempDirectory("sigil-404-")
    val convId = Conversation.id(s"anchor-${rapid.Unique()}")
    TestSigil.setWorkspace(convId, Some(dir))
    val fs = new LocalFileSystemContext(Some(dir))
    val tc = TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
    body(fs, tc).guarantee(Task {
      TestSigil.setWorkspace(convId, None)
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def typedRead(signals: List[Signal]): ReadFileOutput =
    signals.collectFirst { case d: ToolDelta if d.output.exists(_.isInstanceOf[ReadFileOutput]) =>
      d.output.get.asInstanceOf[ReadFileOutput]
    }.getOrElse(fail("no ReadFileOutput in read signals"))

  /** The exact text the model reads for the read — the settled ToolInvoke's
    * projected frame content, not the typed field. */
  private def modelFacingRender(out: ReadFileOutput): String = {
    val invoke = ToolInvoke(
      toolName = ToolName("read_file"), participantId = TestAgent,
      conversationId = Conversation.id("x"), topicId = Id[Topic]("t"),
      output = out, outcome = ToolOutcome.Success, state = EventState.Complete
    )
    FrameBuilder.computeFrame(invoke).collect {
      case tc: ContextFrame.ToolCall => tc.state match {
        case ToolCallState.Complete(c, _) => c
        case _                            => fail("read_file frame not Complete")
      }
    }.getOrElse(fail("no frame for read_file invoke"))
  }

  "A read_file anchor used for edit_file (#404)" should {
    "match when the anchor line is copied from read_file's rendered output" in withWorkspace { (fs, tc) =>
      // A line with BOTH a double-quote and a slash — the class that broke.
      val fileBody = "package x\nval path = \"/home/u/project/x.scala\"\nval y = 2\n"
      for {
        _    <- new WriteFileTool(fs).execute(WriteFileInput("Main.scala", fileBody), tc, Event.id()).toList
        read <- new ReadFileTool(fs).execute(ReadFileInput("Main.scala"), tc, Event.id()).toList
        out   = typedRead(read)
        rendered = modelFacingRender(out)
        // Copy the anchor line verbatim from what the model saw.
        anchor = rendered.linesIterator.find(_.contains("val path")).getOrElse(fail(s"no val-path line in:\n$rendered"))
        edit <- new EditFileTool(fs).execute(
                  EditFileInput(path = "Main.scala", oldString = anchor, newString = "val path = \"/tmp/moved.scala\""),
                  tc, Event.id()
                ).toList
      } yield {
        val editOut = edit.collectFirst { case d: ToolDelta if d.output.exists(_.isInstanceOf[EditFileOutput]) =>
          d.output.get.asInstanceOf[EditFileOutput]
        }.getOrElse(fail(s"no EditFileOutput; signals: ${edit.map(_.getClass.getSimpleName)}"))
        withClue(s"anchor copied from render = [$anchor]\nrendered:\n$rendered\n") {
          // Pre-fix: anchor carried \" and \/ → no match → a Failure, not Success.
          editOut shouldBe a[EditFileOutput.Success]
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
