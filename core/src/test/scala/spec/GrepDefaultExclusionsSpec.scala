package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.{Event, ToolResults}
import sigil.tool.fs.{GrepNode, GrepTool, LocalFileSystemContext}
import sigil.tool.model.GrepInput
import sigil.tool.output.JsonPagedResult

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/**
 * Coverage for the default-noise-directory exclusions on
 * [[GrepTool]]:
 *
 *   1. `.claude/worktrees/` is dropped by default — duplicate copies
 *      of source under Claude Code throwaway worktrees do not flood
 *      the agent's results.
 *   2. `target/` (and the broader build-output set) is dropped.
 *   3. `includeIgnored = true` returns the noise-directory matches
 *      that would otherwise have been filtered.
 *   4. The exclusion is per-path-segment — a file named `target.txt`
 *      whose parent isn't `target/` is NOT excluded just because
 *      `target` appears in its filename.
 */
class GrepDefaultExclusionsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def withTempDir[T](body: Path => Task[T]): Task[T] = Task.defer {
    val dir = Files.createTempDirectory("grep-default-exclusions-")
    body(dir).guarantee(Task {
      if (Files.exists(dir)) {
        val s = Files.walk(dir)
        try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
        finally s.close()
      }
    })
  }

  private def writeFile(p: Path, text: String): Unit = {
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    ()
  }

  private def turnContext(callId: lightdb.id.Id[Event]): TurnContext = {
    val convId  = Conversation.id(s"grep-excl-${rapid.Unique()}")
    val topicId = sigil.conversation.Topic.id(s"grep-excl-topic-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(topicId, "test", "test")),
      _id    = convId
    )
    TurnContext(
      sigil                = TestSigil,
      chain                = List(TestUser),
      conversation         = conv,
      turnInput            = TurnInput(ConversationView(conversationId = convId)),
      currentToolInvokeId  = Some(callId)
    )
  }

  /** Pull the first-page `JsonPagedResult` out of the grep tool's
    * emitted events; FileMatch payloads identify which files matched. */
  private def matchedFiles(events: List[Event]): List[String] = {
    val page = events.collectFirst {
      case t: ToolResults if t.typed.isDefined => t.typed.get.as[JsonPagedResult]
    }.getOrElse(throw new RuntimeException(s"no typed ToolResults found in $events"))
    page.items.map(_.as[GrepNode]).collect { case f: GrepNode.FileMatch => f.filePath }
  }

  private def runGrep(root: Path, includeIgnored: Boolean): Task[List[String]] = {
    val callId = Event.id()
    val ctx    = turnContext(callId)
    val fs     = new LocalFileSystemContext(basePath = Some(root))
    new GrepTool(fs)
      .execute(GrepInput(path = ".", pattern = "TODO", includeIgnored = includeIgnored), ctx)
      .toList
      .map(matchedFiles)
  }

  "GrepTool default-noise exclusions" should {

    "skip .claude/worktrees/ duplicates by default (sole match: file outside .claude)" in withTempDir { root =>
      writeFile(root.resolve("src/main/scala/Foo.scala"), "object Foo { /* TODO real */ }")
      writeFile(root.resolve(".claude/worktrees/agent-X/src/main/scala/Foo.scala"),
                "object Foo { /* TODO worktree */ }")
      runGrep(root, includeIgnored = false).map { files =>
        files shouldBe List("src/main/scala/Foo.scala")
      }
    }

    "skip target/ by default" in withTempDir { root =>
      writeFile(root.resolve("src/main/scala/Bar.scala"), "object Bar { /* TODO */ }")
      writeFile(root.resolve("target/scala-3.8.3/classes/Bar.scala"), "object Bar { /* TODO */ }")
      runGrep(root, includeIgnored = false).map { files =>
        files shouldBe List("src/main/scala/Bar.scala")
      }
    }

    "return matches inside excluded directories when includeIgnored = true" in withTempDir { root =>
      writeFile(root.resolve("src/main/scala/Foo.scala"), "object Foo { /* TODO real */ }")
      writeFile(root.resolve(".claude/worktrees/agent-X/src/main/scala/Foo.scala"),
                "object Foo { /* TODO worktree */ }")
      runGrep(root, includeIgnored = true).map { files =>
        files.toSet shouldBe Set(
          "src/main/scala/Foo.scala",
          ".claude/worktrees/agent-X/src/main/scala/Foo.scala"
        )
      }
    }

    "exclude per directory segment, not per filename — a file literally named target.txt is matched" in withTempDir { root =>
      writeFile(root.resolve("src/main/scala/Foo.scala"), "object Foo { /* TODO */ }")
      // File NAME is "target.txt"; the segment "target" appears as the
      // filename, not as a directory. Per-segment exclusion must keep it.
      writeFile(root.resolve("notes/target.txt"), "TODO review the target story")
      runGrep(root, includeIgnored = false).map { files =>
        files.toSet shouldBe Set(
          "src/main/scala/Foo.scala",
          "notes/target.txt"
        )
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
