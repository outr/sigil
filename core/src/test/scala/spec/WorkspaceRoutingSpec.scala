package spec

import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Message
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext, ReadFileTool, WriteFileTool, WorkspacePathResolver}
import sigil.tool.model.{ReadFileInput, ResponseContent, WriteFileInput}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Coverage for bug #45 — `Sigil.workspaceFor(convId)` lets multiple
 * concurrent conversations route filesystem ops to different
 * project roots. Previously every conversation shared a single root
 * (the `LocalFileSystemContext` constructor's basePath, or JVM cwd
 * when not set), so multi-project agents read each others' files.
 *
 * The resolver runs at the tool boundary: relative input paths
 * resolve against `workspaceFor(conversationId)`; absolute paths
 * pass through unchanged; `None` workspace falls back to the
 * legacy single-root behavior.
 */
class WorkspaceRoutingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  // Two synthetic project workspaces under temp.
  private val projectA: Path = Files.createTempDirectory(s"workspace-a-${rapid.Unique()}-")
  private val projectB: Path = Files.createTempDirectory(s"workspace-b-${rapid.Unique()}-")

  private val convA: Id[Conversation] = Conversation.id(s"workspace-conv-a-${rapid.Unique()}")
  private val convB: Id[Conversation] = Conversation.id(s"workspace-conv-b-${rapid.Unique()}")
  private val convNoWorkspace: Id[Conversation] = Conversation.id(s"workspace-conv-none-${rapid.Unique()}")

  // Wire the per-conversation workspaces. TestSigil's setWorkspace
  // pushes into a ConcurrentHashMap that overrides workspaceFor.
  TestSigil.setWorkspace(convA, Some(projectA))
  TestSigil.setWorkspace(convB, Some(projectB))
  TestSigil.setWorkspace(convNoWorkspace, None)

  private def turnCtx(convId: Id[Conversation]): TurnContext = {
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id = convId
    )
    TurnContext(
      sigil            = TestSigil,
      chain            = List(TestUser),
      conversation     = conv,
      conversationView = ConversationView(conversationId = convId),
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  private def writeProjectFile(p: Path, name: String, contents: String): Path = {
    val file = p.resolve(name)
    Files.writeString(file, contents)
    file
  }

  private def extractJson(events: List[sigil.event.Event]): fabric.Json = {
    events.collectFirst { case m: Message =>
      m.content.collectFirst { case ResponseContent.Text(t) => t }
    }.flatten.map(JsonParser(_)).getOrElse(fabric.Obj.empty)
  }

  // FS context with NO basePath — same shape Sage uses (full
  // filesystem access). Per-conversation rooting comes purely from
  // the WorkspacePathResolver, not from the FS context's sandbox.
  private val fs: FileSystemContext = new LocalFileSystemContext(basePath = None)

  "WorkspacePathResolver" should {

    "resolve a relative path against the conversation's workspace" in {
      WorkspacePathResolver.resolve(turnCtx(convA), "build.sbt").map { resolved =>
        Path.of(resolved).normalize shouldBe projectA.resolve("build.sbt").normalize
      }
    }

    "leave an absolute path untouched even when a workspace is configured" in {
      WorkspacePathResolver.resolve(turnCtx(convA), "/etc/hosts").map { resolved =>
        resolved shouldBe "/etc/hosts"
      }
    }

    "fall through to the relative path when no workspace is configured" in {
      WorkspacePathResolver.resolve(turnCtx(convNoWorkspace), "build.sbt").map { resolved =>
        resolved shouldBe "build.sbt"
      }
    }
  }

  "FS tools with per-conversation workspaces" should {

    "read each conversation's own file by relative path" in {
      writeProjectFile(projectA, "build.sbt", "version := \"a-version\"")
      writeProjectFile(projectB, "build.sbt", "version := \"b-version\"")
      val read = new ReadFileTool(fs)
      for {
        a <- read.execute(ReadFileInput("build.sbt"), turnCtx(convA)).toList
        b <- read.execute(ReadFileInput("build.sbt"), turnCtx(convB)).toList
      } yield {
        extractJson(a).get("content").map(_.asString) shouldBe Some("version := \"a-version\"")
        extractJson(b).get("content").map(_.asString) shouldBe Some("version := \"b-version\"")
      }
    }

    "write to each conversation's own workspace by relative path" in {
      val write = new WriteFileTool(fs)
      for {
        _ <- write.execute(WriteFileInput("notes.md", "from-a"), turnCtx(convA)).toList
        _ <- write.execute(WriteFileInput("notes.md", "from-b"), turnCtx(convB)).toList
      } yield {
        Files.readString(projectA.resolve("notes.md")) shouldBe "from-a"
        Files.readString(projectB.resolve("notes.md")) shouldBe "from-b"
      }
    }

    "honor absolute paths regardless of the conversation's workspace" in {
      val absScratch = Files.createTempFile("workspace-abs-", ".txt")
      Files.writeString(absScratch, "absolute-passthrough")
      val read = new ReadFileTool(fs)
      for {
        out <- read.execute(ReadFileInput(absScratch.toString), turnCtx(convA)).toList
      } yield {
        try {
          extractJson(out).get("content").map(_.asString) shouldBe Some("absolute-passthrough")
        } finally Files.deleteIfExists(absScratch)
      }
    }
  }

  override protected def afterAll(): Unit = {
    List(projectA, projectB).foreach { p =>
      if (Files.exists(p)) {
        Files.walk(p).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
