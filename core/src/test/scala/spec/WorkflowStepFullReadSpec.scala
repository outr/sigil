package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.fs.{LocalFileSystemContext, ReadFileTool, WriteFileTool}
import sigil.tool.model.{ReadFileInput, ReadFileOutput, WriteFileInput}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Sigil #394 — a tool's output is capped for AGENT context management
 * (`read_file` truncates an over-cap read inline with an `offset=… limit=…`
 * continuation note). That's correct when an agent will paginate. But when the
 * tool runs as a WORKFLOW STEP whose `output` feeds a later step, there is no
 * agent to paginate — the next step consumes the variable verbatim, so a capped
 * read corrupts the data flow (the downstream `edit`/`write` step only ever sees
 * the first window). `SyntheticTurnContext` already sets
 * `overflowLargeResults = false` for step execution; `read_file` must honour it
 * and deliver the COMPLETE file.
 */
class WorkflowStepFullReadSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val threshold = TestSigil.inlineContentThreshold

  // A file comfortably over the inline cap so the agent-facing path would cap it.
  private val bigBody: String = (1 to 400).map(i => s"line $i — ${"x" * 60}").mkString("\n")

  private def turnContext(convId: Id[Conversation], overflow: Boolean): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel,
      overflowLargeResults = overflow
    )

  private def withWorkspace[A](body: LocalFileSystemContext => Task[A]): Task[A] = Task.defer {
    val dir = Files.createTempDirectory("sigil-394-")
    val fs = new LocalFileSystemContext(Some(dir))
    body(fs).guarantee(Task {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def typedRead(signals: List[Signal]): ReadFileOutput =
    signals.collectFirst {
      case d: ToolDelta if d.output.exists(_.isInstanceOf[ReadFileOutput]) =>
        d.output.get.asInstanceOf[ReadFileOutput]
    }.getOrElse(fail("no ReadFileOutput in tool signals"))

  "read_file as a workflow step (overflowLargeResults = false)" should {
    "capture the COMPLETE file — no inline cap, no offset/limit pagination note (#394)" in withWorkspace { fs =>
      val convId = Conversation.id(s"wf394-${rapid.Unique()}")
      for {
        _ <- new WriteFileTool(fs).execute(WriteFileInput("big.scala", bigBody), turnContext(convId, overflow = true), Event.id()).toList
        read <- new ReadFileTool(fs).execute(ReadFileInput("big.scala"), turnContext(convId, overflow = false), Event.id()).toList
      } yield {
        val out = typedRead(read)
        bigBody.length.toLong should be > threshold
        out.content shouldBe bigBody
        out.content should not include "offset="
        out.content should not include "read_file again"
        out.content should not include ".sigil/output"
        out.linesRead shouldBe out.totalLines
      }
    }
  }

  "read_file for an agent (overflowLargeResults = true)" should {
    "still cap inline with offset guidance — the agent path is unchanged (#394)" in withWorkspace { fs =>
      val convId = Conversation.id(s"wf394agent-${rapid.Unique()}")
      for {
        _ <- new WriteFileTool(fs).execute(WriteFileInput("big.scala", bigBody), turnContext(convId, overflow = true), Event.id()).toList
        read <- new ReadFileTool(fs).execute(ReadFileInput("big.scala"), turnContext(convId, overflow = true), Event.id()).toList
      } yield {
        val out = typedRead(read)
        out.content.length.toLong should be <= (threshold + 400L)
        out.content should include("offset=")
        out.content.toLowerCase should include("read_file")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
