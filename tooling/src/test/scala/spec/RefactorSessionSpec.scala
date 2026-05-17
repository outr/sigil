package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, GrepMatch, LocalFileSystemContext}
import sigil.tooling.refactor.{
  MatchAction, MatchDecision,
  RefactorApplyInput, RefactorApplyStatus, RefactorApplyTool,
  RefactorCancelInput, RefactorCancelStatus, RefactorCancelTool,
  RefactorSessionStore, RefactorWithInstructionInput,
  RefactorWithInstructionTool, RefactorWorkerDispatcher
}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*

/**
 * Deterministic coverage for the prepare → apply / cancel
 * session contract. The four cases mirror the bug-spec acceptance
 * criteria: prepare returns a sessionId + paginated first page
 * without touching disk, apply commits the staged edits and
 * removes the session, cancel drops the session cleanly, and TTL
 * expiry behaves like cancel.
 *
 * No live LLM — the worker dispatch is stubbed via an injected
 * [[RefactorWorkerDispatcher]] so the test exercises the
 * session-store + apply / cancel + drain machinery without
 * waiting on a real model.
 */
class RefactorSessionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 30.seconds

  /** Worker dispatcher that returns a fixed `Edited` decision for
    * every match handed to it. Tests pass the precomputed
    * (start, end, newText) so the framework's `buildEdit`
    * verification step accepts the decision. */
  private final class FixedDispatcher(startChar: Int,
                                      endChar: Int,
                                      oldText: String,
                                      newText: String) extends RefactorWorkerDispatcher {
    override def dispatch(ctx: TurnContext,
                          modelId: String,
                          filePath: String,
                          matches: List[GrepMatch],
                          instruction: String): Task[Either[String, List[MatchDecision]]] = Task {
      val decisions = matches.map { m =>
        MatchDecision(
          matchedLine = m.lineNumber,
          action      = MatchAction.Edited,
          reason      = "stub",
          oldText     = oldText,
          newText     = Some(newText),
          startChar   = Some(startChar),
          endChar     = Some(endChar)
        )
      }
      Right(decisions)
    }
  }

  private def materialize(files: List[(String, String)]): Path = {
    val root = Files.createTempDirectory(s"refactor-session-${rapid.Unique()}-").toAbsolutePath.normalize
    files.foreach { case (rel, content) =>
      val target = root.resolve(rel)
      Files.createDirectories(target.getParent)
      Files.writeString(target, content,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
    root
  }

  private def cleanup(root: Path): Unit = {
    if (Files.exists(root)) {
      import scala.jdk.CollectionConverters.*
      val s = Files.walk(root)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    }
  }

  private def turnContext(): TurnContext = {
    val convId  = Conversation.id(s"refactor-session-${rapid.Unique()}")
    val callId  = Event.id()
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id    = convId
    )
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    TurnContext(
      sigil               = TestSigil,
      chain               = List(TestUser),
      conversation        = conv,
      turnInput           = TurnInput(ConversationView(conversationId = convId)),
      currentToolInvokeId = Some(callId)
    )
  }

  "Refactor session prepare" should {

    // Acceptance #1: prepare returns a sessionId + paginated first
    // page; no file is modified. Wires a stub dispatcher so the
    // worker path is deterministic.
    "return a sessionId and first-page diffs without writing files" in {
      val store     = new RefactorSessionStore()
      val workspace = materialize(List(
        "a.txt" -> "alpha\n// TODO:remove first\nbeta\n",
        "b.txt" -> "gamma\n// TODO:remove second\ndelta\n"
      ))
      val originals = Map(
        "a.txt" -> Files.readString(workspace.resolve("a.txt")),
        "b.txt" -> Files.readString(workspace.resolve("b.txt"))
      )
      val fs  = new LocalFileSystemContext(basePath = Some(workspace))
      val ctx = turnContext()
      val dispatcher = new FixedDispatcher(startChar = 0, endChar = 14, oldText = "// TODO:remove", newText = "")
      val tool = new RefactorWithInstructionTool(fs, store, workerDispatcher = Some(dispatcher))
      val input = RefactorWithInstructionInput(
        path        = workspace.toString,
        glob        = None,
        findPattern = "// TODO:remove",
        instruction = "stub-driven",
        maxParallel = 2,
        maxWorkers  = 5
      )
      tool.invoke(input, ctx).map { out =>
        try {
          out.sessionId should not be empty
          out.abortReason shouldBe None
          out.totalDiffs shouldBe 2
          out.page0Diffs.size shouldBe 2
          out.hasMore shouldBe false
          out.nodeIds.size shouldBe 2
          out.referenceId shouldBe out.sessionId
          out.callId.value shouldBe out.sessionId
          // The session must be present in the store after prepare.
          store.peek(out.sessionId) shouldBe defined
          // No file modified by prepare.
          Files.readString(workspace.resolve("a.txt")) shouldBe originals("a.txt")
          Files.readString(workspace.resolve("b.txt")) shouldBe originals("b.txt")
        } finally cleanup(workspace)
      }
    }
  }

  "Refactor session apply" should {

    // Acceptance #2: apply commits the prepared diffs atomically;
    // session disappears (a second apply returns not-found).
    "commit the prepared diffs and remove the session" in {
      val store     = new RefactorSessionStore()
      val workspace = materialize(List(
        "a.txt" -> "alpha\n// TODO:remove first\nbeta\n",
        "b.txt" -> "gamma\n// TODO:remove second\ndelta\n"
      ))
      val fs  = new LocalFileSystemContext(basePath = Some(workspace))
      val ctx = turnContext()
      val dispatcher = new FixedDispatcher(startChar = 0, endChar = 14, oldText = "// TODO:remove", newText = "")
      val prepareTool = new RefactorWithInstructionTool(fs, store, workerDispatcher = Some(dispatcher))
      val applyTool   = new RefactorApplyTool(fs, store)
      val input = RefactorWithInstructionInput(
        path        = workspace.toString,
        glob        = None,
        findPattern = "// TODO:remove",
        instruction = "stub-driven",
        maxParallel = 2,
        maxWorkers  = 5
      )
      for {
        prepared <- prepareTool.invoke(input, ctx)
        applied  <- applyTool.invoke(RefactorApplyInput(prepared.sessionId), ctx)
        twice    <- applyTool.invoke(RefactorApplyInput(prepared.sessionId), ctx)
      } yield {
        try {
          applied.status shouldBe RefactorApplyStatus.Applied
          applied.filesModified shouldBe 2
          applied.perFile.size shouldBe 2
          // Both files now have the marker removed.
          Files.readString(workspace.resolve("a.txt")) should not include "// TODO:remove"
          Files.readString(workspace.resolve("b.txt")) should not include "// TODO:remove"
          // Subsequent apply against the same session id returns
          // not-found — the session was consumed.
          twice.status shouldBe RefactorApplyStatus.NotFound
          twice.filesModified shouldBe 0
          // Session no longer in the store.
          store.peek(prepared.sessionId) shouldBe None
        } finally cleanup(workspace)
      }
    }
  }

  "Refactor session cancel" should {

    // Acceptance #3: cancel drops the session cleanly; no file
    // modified; subsequent apply returns not-found.
    "drop the session without writing files" in {
      val store     = new RefactorSessionStore()
      val workspace = materialize(List(
        "a.txt" -> "alpha\n// TODO:remove first\nbeta\n"
      ))
      val original = Files.readString(workspace.resolve("a.txt"))
      val fs  = new LocalFileSystemContext(basePath = Some(workspace))
      val ctx = turnContext()
      val dispatcher = new FixedDispatcher(startChar = 0, endChar = 14, oldText = "// TODO:remove", newText = "")
      val prepareTool = new RefactorWithInstructionTool(fs, store, workerDispatcher = Some(dispatcher))
      val cancelTool  = new RefactorCancelTool(store)
      val applyTool   = new RefactorApplyTool(fs, store)
      val input = RefactorWithInstructionInput(
        path        = workspace.toString,
        glob        = None,
        findPattern = "// TODO:remove",
        instruction = "stub-driven",
        maxParallel = 1,
        maxWorkers  = 5
      )
      for {
        prepared   <- prepareTool.invoke(input, ctx)
        cancelled  <- cancelTool.invoke(RefactorCancelInput(prepared.sessionId), ctx)
        afterApply <- applyTool.invoke(RefactorApplyInput(prepared.sessionId), ctx)
        twice      <- cancelTool.invoke(RefactorCancelInput(prepared.sessionId), ctx)
      } yield {
        try {
          cancelled.status shouldBe RefactorCancelStatus.Cancelled
          // File untouched.
          Files.readString(workspace.resolve("a.txt")) shouldBe original
          // Apply after cancel returns not-found.
          afterApply.status shouldBe RefactorApplyStatus.NotFound
          // Cancel is idempotent — a second cancel on the same id
          // returns not-found rather than re-running the drop.
          twice.status shouldBe RefactorCancelStatus.NotFound
          // Store empty.
          store.peek(prepared.sessionId) shouldBe None
        } finally cleanup(workspace)
      }
    }
  }

  "Refactor session TTL" should {

    // Acceptance #4: a session past TTL behaves like cancel. We
    // drive the clock via the store's `evictExpired(now)` so the
    // test is deterministic; production paths call this on a
    // background sweep.
    "evict expired sessions so apply returns not-found and no files change" in {
      val ttl       = 100.millis
      val store     = new RefactorSessionStore(ttl = ttl)
      val workspace = materialize(List(
        "a.txt" -> "alpha\n// TODO:remove first\nbeta\n"
      ))
      val original = Files.readString(workspace.resolve("a.txt"))
      val fs  = new LocalFileSystemContext(basePath = Some(workspace))
      val ctx = turnContext()
      val dispatcher = new FixedDispatcher(startChar = 0, endChar = 14, oldText = "// TODO:remove", newText = "")
      val prepareTool = new RefactorWithInstructionTool(fs, store, workerDispatcher = Some(dispatcher))
      val applyTool   = new RefactorApplyTool(fs, store)
      val input = RefactorWithInstructionInput(
        path        = workspace.toString,
        glob        = None,
        findPattern = "// TODO:remove",
        instruction = "stub-driven",
        maxParallel = 1,
        maxWorkers  = 5
      )
      for {
        prepared <- prepareTool.invoke(input, ctx)
        // Simulate clock advance past TTL — the store evicts based
        // on the supplied `now` argument so the test doesn't have
        // to sleep.
        evicted   = store.evictExpired(System.currentTimeMillis() + ttl.toMillis + 1000)
        applied  <- applyTool.invoke(RefactorApplyInput(prepared.sessionId), ctx)
      } yield {
        try {
          evicted shouldBe 1
          applied.status shouldBe RefactorApplyStatus.NotFound
          applied.filesModified shouldBe 0
          // File untouched by either prepare or the failed apply.
          Files.readString(workspace.resolve("a.txt")) shouldBe original
          store.peek(prepared.sessionId) shouldBe None
        } finally cleanup(workspace)
      }
    }
  }
}
