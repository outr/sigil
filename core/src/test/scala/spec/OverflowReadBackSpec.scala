package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.{Event, ToolOutcome}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.fs.{LocalFileSystemContext, ReadFileTool, WriteFileTool}
import sigil.tool.model.{ReadFileInput, ReadFileOutput, WriteFileInput}
import sigil.tool.process.{ProcessOutputTool, ProcessRegistry}
import sigil.tool.model.ProcessOutputInput
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolContext,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Regression for sigil #370 — an externalized (overflow) tool result must be
 * recoverable without cascading. Reading the overflow file back must NOT
 * re-overflow into yet another file, the guidance must be consistent (always
 * `read_file` with offset/limit — never a removed `tool_output_get`), and a
 * `.sigil/output/...` path handed to `process_output` must redirect, not produce
 * an opaque "No process handle".
 */
class OverflowReadBackSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val threshold = TestSigil.inlineContentThreshold

  private def withWorkspace[A](body: (LocalFileSystemContext, TurnContext) => Task[A]): Task[A] = Task.defer {
    val dir = Files.createTempDirectory("sigil-overflow-")
    val convId = Conversation.id(s"overflow-${rapid.Unique()}")
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
    signals.collectFirst {
      case d: ToolDelta if d.output.exists(_.isInstanceOf[ReadFileOutput]) =>
        d.output.get.asInstanceOf[ReadFileOutput]
    }.getOrElse(fail(s"no ReadFileOutput in: ${signals.collect { case d: ToolDelta => d.output.map(_.getClass.getSimpleName) }}"))

  // A wide, multi-line body well over the inline cap — the shape a discovery
  // tool produces (newline-separated paths).
  private val pathLines: List[String] = (1 to 400).map(i => s"core/src/main/scala/sigil/generated/item-$i-${"x" * 60}.scala").toList
  private val bigBody: String = pathLines.mkString("\n")

  "read_file on an over-cap file" should {
    "return a bounded, inline result with offset guidance — never re-externalize or mention tool_output_get (#370)" in withWorkspace {
      (fs, tc) =>
        for {
          _ <- new WriteFileTool(fs).execute(WriteFileInput("matches.txt", bigBody), tc, Event.id()).toList
          read <- new ReadFileTool(fs).execute(ReadFileInput("matches.txt"), tc, Event.id()).toList
        } yield {
          val out = typedRead(read)
          withClue(s"content len=${out.content.length} threshold=$threshold head=${out.content.take(60)}\n") {
            // Bounded inline — fits the cap (plus a short guidance note), not the full body.
            bigBody.length.toLong should be > threshold
            out.content.length.toLong should be <= (threshold + 400L)
            // Consistent, actionable continuation guidance.
            out.content should include("offset=")
            out.content.toLowerCase should include("read_file")
            // The removed reference-handle tool must never be named.
            out.content should not include "tool_output_get"
            // No cascade: read-back did not spill into another overflow file.
            read.collect { case d: ToolDelta => d.summary }.flatten.mkString should not include ".sigil/output"
            out.content should not include ".sigil/output"
          }
        }
    }

    "continue from the reported offset to read the remainder" in withWorkspace { (fs, tc) =>
      for {
        _ <- new WriteFileTool(fs).execute(WriteFileInput("matches.txt", bigBody), tc, Event.id()).toList
        first <- new ReadFileTool(fs).execute(ReadFileInput("matches.txt"), tc, Event.id()).toList
        shown = typedRead(first).linesRead
        next <- new ReadFileTool(fs).execute(ReadFileInput("matches.txt", offset = Some(shown), limit = Some(50)), tc, Event.id()).toList
      } yield {
        val out = typedRead(next)
        withClue(s"shown=$shown next.head=${out.content.take(60)}\n") {
          shown should be > 0
          out.content should include(pathLines(shown)) // the next unseen line
        }
      }
    }
  }

  "an externalized TextToolOutput result" should {
    "write the UNWRAPPED text to the overflow file, not the {\"text\":…} envelope (#370/#305)" in withWorkspace { (fs, tc) =>
      val eventId = Event.id()
      BigDiscoveryTool.execute(OverflowDiscoveryInput(), tc, eventId).toList.flatMap { signals =>
        val summary = signals.collect { case d: ToolDelta => d.summary }.flatten.mkString
        withClue(s"summary=$summary\n") {
          summary should include(".sigil/output")
          summary.toLowerCase should include("read_file")
          // The pointer prints the ABSOLUTE path — a relative path
          // forces the model to guess the resolution root, and
          // `.sigil` is hidden from default glob/grep sweeps, so a
          // wrong guess turns one read into a dead workspace hunt.
          val pointerPath = summary.split("written to ").last.split("\\.txt").head + ".txt"
          pointerPath should startWith("/")
          // read_file resolves the shown path as-is (sandbox accepts
          // in-base absolute paths).
          fs.readFile(pointerPath).sync() shouldBe bigBody
        }
        val relPath = s".sigil/output/${tc.conversation.id.value}/big_discovery-${eventId.value}.txt"
        fs.readFile(relPath).map { fileContent =>
          withClue(s"file head=${fileContent.take(60)}\n") {
            fileContent should not include "{\"text\""
            fileContent should startWith("core/src/main/scala")
            fileContent shouldBe bigBody
          }
        }
      }
    }
  }

  "process_output handed a .sigil/output path" should {
    "redirect to read_file instead of an opaque 'No process handle' (#370)" in {
      val tc = TurnContext(
        sigil = TestSigil,
        chain = List(TestUser),
        conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = Conversation.id(s"po-${rapid.Unique()}")),
        turnInput = TurnInput(ConversationView(conversationId = Conversation.id("po"))),
        model = TestSigil.defaultTestModel
      )
      val tool = new ProcessOutputTool(new ProcessRegistry())
      tool.execute(ProcessOutputInput(handle = ".sigil/output/conv-1/grep-abc.txt"), tc, Event.id()).toList.map { signals =>
        val failure = signals.collectFirst {
          case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) => d.summary.getOrElse("")
        }.getOrElse(fail(s"expected a recoverable failure; saw: ${signals.map(_.getClass.getSimpleName)}"))
        withClue(s"failure=$failure\n") {
          failure.toLowerCase should include("read_file")
          failure.toLowerCase should include("not a process handle")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }

  // A discovery-shaped tool whose TextToolOutput overflows the inline cap.
  private case class OverflowDiscoveryInput() extends ToolInput derives RW
  private case object BigDiscoveryTool extends Tool {
    type Input = OverflowDiscoveryInput
    type Output = TextToolOutput
    val io: ToolIO[OverflowDiscoveryInput, TextToolOutput] = ToolIO.derived[OverflowDiscoveryInput, TextToolOutput]
    override val name = ToolName("big_discovery")
    override val description = "Emits a large newline-separated path list (discovery-shaped, over the inline cap)."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "discovery", "overflow"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

    private def executeResult(input: OverflowDiscoveryInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(bigBody)))
  }
}
