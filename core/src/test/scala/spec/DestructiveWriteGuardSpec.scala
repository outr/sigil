package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.signal.{Signal, ToolDelta}
import sigil.event.ToolOutcome
import sigil.tool.fs.{LocalFileSystemContext, WriteFileTool}
import sigil.tool.model.{WriteFileInput, WriteFileOutput}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Sigil #395 — `write_file` must never silently destroy an existing non-empty
 * file by overwriting it with content that is self-evidently not a real
 * rewrite: a tool-call / pagination placeholder (`[read_file: offset=… limit=…]`),
 * a lone unresolved `{{var}}`, or a drastic collapse (a 200-line source file
 * replaced by one line). The guard fires only on OVERWRITES of existing
 * non-empty files; creating a new file with short content is unaffected, and a
 * legitimate edit still commits. A `force = true` flag opts out for the rare
 * intentional truncate-to-nothing case.
 */
class DestructiveWriteGuardSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val original: String = (1 to 200).map(i => s"val x$i = $i // a real line of source $i").mkString("\n")

  private def turnContext(convId: Id[Conversation]): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )

  private def withWorkspace[A](body: (LocalFileSystemContext, TurnContext) => Task[A]): Task[A] = Task.defer {
    val dir = Files.createTempDirectory("sigil-395-")
    val convId = Conversation.id(s"wf395-${rapid.Unique()}")
    val fs = new LocalFileSystemContext(Some(dir))
    body(fs, turnContext(convId)).guarantee(Task {
      val s = Files.walk(dir)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    })
  }

  private def isFailure(signals: List[Signal]): Boolean =
    signals.exists { case d: ToolDelta => d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]); case _ => false }

  private def succeeded(signals: List[Signal]): Boolean =
    signals.exists {
      case d: ToolDelta => d.output.exists(_.isInstanceOf[WriteFileOutput.Success])
      case _ => false
    }

  "write_file overwriting an existing non-empty file" should {

    "REFUSE a tool-call/pagination placeholder and leave the original untouched (#395)" in withWorkspace { (fs, tc) =>
      for {
        _ <- fs.writeFile("Probe.scala", original)
        result <- new WriteFileTool(fs).execute(
          WriteFileInput("Probe.scala", "[read_file: offset=152 limit=200]"),
          tc,
          Event.id()).toList
        onDisk <- fs.readFile("Probe.scala")
      } yield {
        isFailure(result) shouldBe true
        succeeded(result) shouldBe false
        onDisk shouldBe original
      }
    }

    "REFUSE a lone unresolved {{var}} and leave the original untouched (#395)" in withWorkspace { (fs, tc) =>
      for {
        _ <- fs.writeFile("Probe.scala", original)
        result <- new WriteFileTool(fs).execute(WriteFileInput("Probe.scala", "{{edited}}"), tc, Event.id()).toList
        onDisk <- fs.readFile("Probe.scala")
      } yield {
        isFailure(result) shouldBe true
        onDisk shouldBe original
      }
    }

    "REFUSE a drastic collapse (200 lines → 1 line) and leave the original untouched (#395)" in withWorkspace { (fs, tc) =>
      for {
        _ <- fs.writeFile("Probe.scala", original)
        result <- new WriteFileTool(fs).execute(WriteFileInput("Probe.scala", "oops"), tc, Event.id()).toList
        onDisk <- fs.readFile("Probe.scala")
      } yield {
        isFailure(result) shouldBe true
        onDisk shouldBe original
      }
    }

    "ALLOW a legitimate edit (200 lines → 196 lines, real content) (#395)" in withWorkspace { (fs, tc) =>
      val edited = (1 to 196).map(i => s"val y$i = $i // still a real line $i").mkString("\n")
      for {
        _ <- fs.writeFile("Probe.scala", original)
        result <- new WriteFileTool(fs).execute(WriteFileInput("Probe.scala", edited), tc, Event.id()).toList
        onDisk <- fs.readFile("Probe.scala")
      } yield {
        succeeded(result) shouldBe true
        onDisk shouldBe edited
      }
    }

    "ALLOW a destructive collapse when force = true (#395)" in withWorkspace { (fs, tc) =>
      for {
        _ <- fs.writeFile("Probe.scala", original)
        result <- new WriteFileTool(fs).execute(WriteFileInput("Probe.scala", "intentionally tiny", force = true), tc, Event.id()).toList
        onDisk <- fs.readFile("Probe.scala")
      } yield {
        succeeded(result) shouldBe true
        onDisk shouldBe "intentionally tiny"
      }
    }
  }

  "write_file creating a new file" should {
    "ALLOW short content — the guard is only for overwrites of existing non-empty files (#395)" in withWorkspace { (fs, tc) =>
      for {
        result <- new WriteFileTool(fs).execute(WriteFileInput("fresh.txt", "hi"), tc, Event.id()).toList
        onDisk <- fs.readFile("fresh.txt")
      } yield {
        succeeded(result) shouldBe true
        onDisk shouldBe "hi"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
