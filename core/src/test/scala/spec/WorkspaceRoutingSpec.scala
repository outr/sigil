package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.{ToolContext, ToolName}
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext, ReadFileTool, WriteFileTool, WorkspacePathResolver}
import sigil.tool.model.{ReadFileInput, ReadFileOutput, WriteFileInput}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import sigil.event.Event

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
      turnInput        = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  /** Build a [[ToolContext]] for callers (like [[WorkspacePathResolver]])
    * that take ToolContext rather than TurnContext. */
  private def toolCtx(convId: Id[Conversation]): ToolContext =
    ToolContext(turnCtx(convId), Event.id(), ToolName("workspace_test"))

  private def writeProjectFile(p: Path, name: String, contents: String): Path = {
    val file = p.resolve(name)
    Files.writeString(file, contents)
    file
  }

  /** Recover the typed payload from the settling [[ToolDelta]]'s
    * `output` — the same concrete instance the tool produced, no
    * JSON round-trip. */
  private def typed[T <: sigil.tool.ToolOutput](signals: List[Signal])(using ct: ClassTag[T]): T =
    signals.collectFirst {
      case d: ToolDelta if d.output.exists(o => ct.runtimeClass.isInstance(o)) =>
        d.output.get.asInstanceOf[T]
    }.getOrElse(fail(
      s"expected ToolOutput of type ${ct.runtimeClass.getSimpleName}; saw outputs: " +
        signals.collect { case d: ToolDelta => d.output.map(_.getClass.getSimpleName) }.mkString(", ")
    ))

  // FS context with NO basePath — same shape Sage uses (full
  // filesystem access). Per-conversation rooting comes purely from
  // the WorkspacePathResolver, not from the FS context's sandbox.
  private val fs: FileSystemContext = new LocalFileSystemContext(basePath = None)

  "WorkspacePathResolver" should {

    "resolve a relative path against the conversation's workspace" in {
      WorkspacePathResolver.resolve(toolCtx(convA), "build.sbt").map { resolved =>
        Path.of(resolved).normalize shouldBe projectA.resolve("build.sbt").normalize
      }
    }

    "leave an absolute path untouched even when a workspace is configured" in {
      WorkspacePathResolver.resolve(toolCtx(convA), "/etc/hosts").map { resolved =>
        resolved shouldBe "/etc/hosts"
      }
    }

    "fall through to the relative path when no workspace is configured" in {
      WorkspacePathResolver.resolve(toolCtx(convNoWorkspace), "build.sbt").map { resolved =>
        resolved shouldBe "build.sbt"
      }
    }

    // #325 — a delegate_task worker conversation has no workspace of its
    // own (the app only binds one to the user-facing parent). Without
    // the parent-chain fallthrough the worker can discover grep/read_file
    // but has no project root to run them against, and spins to its
    // iteration cap reporting "no workspace path." The resolver must
    // inherit the parent's workspace via parentConversationId.
    "inherit the parent conversation's workspace via parentConversationId for a worker" in {
      val parentId = Conversation.id(s"ws-parent-${rapid.Unique()}")
      val workerId = Conversation.id(s"ws-worker-${rapid.Unique()}")
      TestSigil.setWorkspace(parentId, Some(projectA))  // worker itself: none
      val workerConv = Conversation(
        topics               = List(TopicEntry(TestTopicId, "worker", "worker")),
        parentConversationId = Some(parentId),
        _id                  = workerId
      )
      val workerCtx = ToolContext(
        TurnContext(
          sigil        = TestSigil,
          chain        = List(TestUser),
          conversation = workerConv,
          turnInput    = TurnInput(ConversationView(conversationId = workerId)),
          model        = TestSigil.defaultTestModel
        ),
        Event.id(),
        ToolName("workspace_test")
      )
      for {
        _        <- TestSigil.withDB(_.conversations.transaction(_.upsert(workerConv)))
        resolved <- WorkspacePathResolver.resolve(workerCtx, "build.sbt")
      } yield Path.of(resolved).normalize shouldBe projectA.resolve("build.sbt").normalize
    }
  }

  "FS tools with per-conversation workspaces" should {

    "read each conversation's own file by relative path" in {
      writeProjectFile(projectA, "build.sbt", "version := \"a-version\"")
      writeProjectFile(projectB, "build.sbt", "version := \"b-version\"")
      val read = new ReadFileTool(fs)
      for {
        a <- read.execute(ReadFileInput("build.sbt"), turnCtx(convA), Event.id()).toList
        b <- read.execute(ReadFileInput("build.sbt"), turnCtx(convB), Event.id()).toList
      } yield {
        typed[ReadFileOutput](a).content shouldBe "version := \"a-version\""
        typed[ReadFileOutput](b).content shouldBe "version := \"b-version\""
      }
    }

    "write to each conversation's own workspace by relative path" in {
      val write = new WriteFileTool(fs)
      for {
        _ <- write.execute(WriteFileInput("notes.md", "from-a"), turnCtx(convA), Event.id()).toList
        _ <- write.execute(WriteFileInput("notes.md", "from-b"), turnCtx(convB), Event.id()).toList
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
        out <- read.execute(ReadFileInput(absScratch.toString), turnCtx(convA), Event.id()).toList
      } yield {
        try {
          typed[ReadFileOutput](out).content shouldBe "absolute-passthrough"
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
