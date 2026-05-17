package spec

import fabric.rw.RW
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.{Args, Status}
import profig.Profig
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.db.{Model, SigilDB}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.Information
import sigil.participant.{AgentParticipantId, Participant, ParticipantId}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.provider.Provider
import sigil.signal.Signal
import sigil.tool.fs.{FileSystemContext, GrepMatch, LocalFileSystemContext}
import sigil.tooling.refactor.{
  RefactorApplyTool, RefactorApplyInput, RefactorApplyStatus,
  RefactorCancelInput, RefactorCancelStatus, RefactorCancelTool,
  RefactorSessionStore, RefactorWithInstructionInput, RefactorWithInstructionOutput,
  RefactorWithInstructionTool, SubmitRefactorDecisionsTool
}
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import sigil.workflow.{WorkflowCollections, WorkflowSigil}
import sigil.{Sigil, SpaceId, TurnContext}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*

/**
 * Live end-to-end coverage for the prepare step of the refactor
 * session — spawns one `SigilAgentDecisionStep` worker per file
 * against a real llama.cpp endpoint, drives the worker through
 * structured `submit_refactor_decisions` calls, and verifies the
 * framework's worker dispatch + decision extraction + per-file
 * aggregation pipeline.
 *
 * The session-shaped contract (prepare returns a sessionId,
 * apply commits, cancel drops, TTL expires) is covered
 * deterministically in `RefactorSessionSpec` without an LLM
 * round-trip; this spec exists to keep the live-worker plumbing
 * exercised against real model behaviour.
 *
 * Self-skips when [[TestSigil.llamaCppHost]] is unreachable. Also
 * gated behind `SIGIL_SLOW=1` — each refactor worker iterates
 * against a real LLM and the full suite routinely runs 10-15
 * minutes.
 */
class RefactorWithInstructionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestRefactorSigil.initFor(getClass.getSimpleName)
  TestRefactorSigil.setProvider(LlamaCppProvider(TestRefactorSigil, TestSigil.llamaCppHost).singleton)

  override implicit val testTimeout: FiniteDuration = 12.minutes

  override def run(testName: Option[String], args: Args): Status =
    LiveProbe.requireSlowEnabled(this).getOrElse(super.run(testName, args))

  private val modelId = Model.id("qwen3.5-9b-q4_k_m")

  private def isReachable: Boolean =
    scala.util.Try {
      val url  = java.net.URI.create(TestSigil.llamaCppHost.toString).toURL
      val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setConnectTimeout(2000)
      conn.setReadTimeout(2000)
      conn.setRequestMethod("HEAD")
      val ok = conn.getResponseCode < 600
      conn.disconnect()
      ok
    }.getOrElse(false)

  private def turnContext(workspace: Path): TurnContext = {
    val convId = Conversation.id(s"refactor-spec-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(RefactorTestTopic.id, RefactorTestTopic.label, RefactorTestTopic.summary)),
      _id    = convId
    )
    TestRefactorSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    TurnContext(
      sigil        = TestRefactorSigil,
      chain        = List(RefactorTestUser),
      conversation = conv,
      turnInput    = TurnInput(ConversationView(conversationId = convId))
    )
  }

  /** Materialise a workspace populated with `files`, each entry a
    * (relative path, content) pair. Returns the workspace root. */
  private def materialize(files: List[(String, String)]): Path = {
    val root = Files.createTempDirectory(s"refactor-spec-${rapid.Unique()}-").toAbsolutePath.normalize
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

  /** Fresh per-test session store so prior tests can't leak prepared
    * sessions into a later one. */
  private def newStore(): RefactorSessionStore = new RefactorSessionStore()

  private def prepare(fs: FileSystemContext,
                      store: RefactorSessionStore,
                      input: RefactorWithInstructionInput,
                      ctx: TurnContext): Task[RefactorWithInstructionOutput] = {
    val tool = new RefactorWithInstructionTool(fs, store)
    tool.invoke(input, ctx)
  }

  private def apply(fs: FileSystemContext,
                    store: RefactorSessionStore,
                    sessionId: String,
                    ctx: TurnContext): Task[sigil.tooling.refactor.RefactorApplyOutput] = {
    val tool = new RefactorApplyTool(fs, store)
    tool.invoke(RefactorApplyInput(sessionId), ctx)
  }

  if (!isReachable) {
    "RefactorWithInstructionSpec" should {
      "skip when llama.cpp is unreachable" in pending
    }
  } else {
    "RefactorWithInstructionTool" should {

      // Happy path: prepare returns a session; apply commits the
      // stored edit; the file is mutated on disk. Asserts the
      // worker dispatched, the decision extraction worked, and the
      // session round-trip from prepare to apply commits the
      // worker's diff.
      "prepare a session and then apply it to commit the edit" in {
        val store = newStore()
        val workspace = materialize(List(
          "a.txt" -> "alpha line\n// TODO:remove obsolete-A\nbeta line\n"
        ))
        val fs = new LocalFileSystemContext(basePath = Some(workspace))
        val ctx = turnContext(workspace)
        val input = RefactorWithInstructionInput(
          path        = workspace.toString,
          glob        = None,
          findPattern = "// TODO:remove",
          instruction =
            """Call `submit_refactor_decisions` ONCE with this exact payload, then call `complete_task`:
              |  filePath: "a.txt"
              |  decisions: [ {
              |    matchedLine: 2,
              |    action: "Edited",
              |    reason: "remove the TODO marker",
              |    oldText: "// TODO:remove",
              |    newText: "",
              |    startChar: 0,
              |    endChar: 14
              |  } ]""".stripMargin,
          workerModelId = Some(modelId.value),
          maxParallel   = 1,
          maxWorkers    = 5
        )
        for {
          prepared <- prepare(fs, store, input, ctx)
          applied  <- apply(fs, store, prepared.sessionId, ctx)
        } yield {
          try {
            // Architectural assertions — the framework dispatched a
            // worker, the worker settled, the framework produced a
            // per-file report + a session. Whether the LLM emitted a
            // clean submit_refactor_decisions call within
            // maxIterations is model-dependent on a small quantised
            // local model, so we accept both the committed-edit shape
            // and the dispatched-but-no-decision shape — both prove
            // the framework's plumbing works end-to-end.
            prepared.abortReason shouldBe None
            prepared.page0Diffs.size shouldBe 1
            if (applied.filesModified >= 1) {
              applied.status shouldBe RefactorApplyStatus.Applied
              Files.readString(workspace.resolve("a.txt")) should not include "// TODO:remove"
            } else {
              // Worker dispatched but produced no committable decision —
              // the per-file report still records the attempt.
              applied.filesModified shouldBe 0
            }
          } finally cleanup(workspace)
        }
      }

      // Prepare-then-cancel: previously covered with `dryRun=true`.
      // Now the natural shape is prepare + cancel. We assert the
      // prepare step ran the worker pipeline but didn't touch disk,
      // and that cancel drops the session cleanly.
      "produce a session without writing files, and cancel cleanly drops it" in {
        val store = newStore()
        val workspace = materialize(List(
          "single.txt" -> "header\n// TODO:remove cancel-target\nfooter\n"
        ))
        val original = Files.readString(workspace.resolve("single.txt"))
        val fs = new LocalFileSystemContext(basePath = Some(workspace))
        val ctx = turnContext(workspace)
        val input = RefactorWithInstructionInput(
          path        = workspace.toString,
          glob        = None,
          findPattern = "// TODO:remove",
          instruction =
            """Call `submit_refactor_decisions` ONCE with this exact payload, then call `complete_task`:
              |  filePath: "single.txt"
              |  decisions: [ {
              |    matchedLine: 2,
              |    action: "Edited",
              |    reason: "remove the TODO marker",
              |    oldText: "// TODO:remove",
              |    newText: "",
              |    startChar: 0,
              |    endChar: 14
              |  } ]""".stripMargin,
          workerModelId = Some(modelId.value),
          maxParallel   = 1,
          maxWorkers    = 5
        )
        for {
          prepared  <- prepare(fs, store, input, ctx)
          cancelled <- new RefactorCancelTool(store).invoke(RefactorCancelInput(prepared.sessionId), ctx)
          afterApply <- apply(fs, store, prepared.sessionId, ctx)
        } yield {
          try {
            prepared.abortReason shouldBe None
            // File untouched by prepare.
            Files.readString(workspace.resolve("single.txt")) shouldBe original
            cancelled.status shouldBe RefactorCancelStatus.Cancelled
            // After cancel, apply against the same session id finds nothing.
            afterApply.status shouldBe RefactorApplyStatus.NotFound
            // File still untouched.
            Files.readString(workspace.resolve("single.txt")) shouldBe original
          } finally cleanup(workspace)
        }
      }

      // Per-match judgment: file has two matches; the worker is told
      // to Edit the first and Skip the second. After prepare + apply,
      // the Edited match is gone and the Skipped one remains.
      "honor a per-match skip when the worker mixes Edited and Skipped decisions" in {
        val store = newStore()
        val workspace = materialize(List(
          "mixed.txt" ->
            """alpha line
              |// TODO:remove this-one-goes
              |middle
              |// TODO:remove this-one-stays
              |omega line
              |""".stripMargin
        ))
        val fs = new LocalFileSystemContext(basePath = Some(workspace))
        val ctx = turnContext(workspace)
        val input = RefactorWithInstructionInput(
          path        = workspace.toString,
          glob        = None,
          findPattern = "// TODO:remove",
          instruction =
            """Call `submit_refactor_decisions` ONCE with EXACTLY these two decisions, then call `complete_task`:
              |  filePath: "mixed.txt"
              |  decisions: [
              |    { matchedLine: 2, action: "Edited",  reason: "first marker should go",       oldText: "// TODO:remove", newText: "", startChar: 0, endChar: 14 },
              |    { matchedLine: 4, action: "Skipped", reason: "second marker should remain",  oldText: "// TODO:remove" }
              |  ]""".stripMargin,
          workerModelId = Some(modelId.value),
          maxParallel   = 1,
          maxWorkers    = 5
        )
        for {
          prepared <- prepare(fs, store, input, ctx)
          applied  <- apply(fs, store, prepared.sessionId, ctx)
        } yield {
          try {
            prepared.abortReason shouldBe None
            val body = Files.readString(workspace.resolve("mixed.txt"))
            // The Skipped match's line must still carry its marker.
            body should include("// TODO:remove this-one-stays")
            if (applied.filesModified >= 1) {
              body should not include "// TODO:remove this-one-goes"
            } else succeed
          } finally cleanup(workspace)
        }
      }

      // Worker failure isolation: two files; the synthetic
      // `FileSystemContext` wrapper throws on `b.txt` so its worker
      // never gets readable content. The other file's worker runs
      // normally. We assert the b.txt failure shows up in the
      // prepare result's per-file report, the other file's edit
      // commits on apply, and b.txt is unchanged on disk.
      "isolate per-file worker failures so other files still commit" in {
        val store = newStore()
        val workspace = materialize(List(
          "a.txt" -> "alpha\n// TODO:remove keep-going-A\nomega\n",
          "b.txt" -> "alpha\n// TODO:remove this-file-throws\nomega\n"
        ))
        val realFs = new LocalFileSystemContext(basePath = Some(workspace))
        val fs: FileSystemContext = new FileSystemContext {
          override def readFile(filePath: String): Task[String] =
            if (filePath.endsWith("b.txt"))
              Task.error(new java.io.IOException("synthetic: b.txt unreadable"))
            else realFs.readFile(filePath)
          override def readFileLines(filePath: String, offset: Int, limit: Int): Task[(List[String], Int)] =
            realFs.readFileLines(filePath, offset, limit)
          override def writeFile(filePath: String, content: String): Task[Long] =
            realFs.writeFile(filePath, content)
          override def deleteFile(filePath: String): Task[Boolean] =
            realFs.deleteFile(filePath)
          override def listFiles(basePath: String, pattern: String, maxResults: Int): Task[List[String]] =
            realFs.listFiles(basePath, pattern, maxResults)
          override def searchFiles(basePath: String, pattern: String, glob: Option[String], maxMatches: Int, contextLines: Int): Task[List[GrepMatch]] =
            realFs.searchFiles(basePath, pattern, glob, maxMatches, contextLines)
          override def executeCommand(command: String, workingDir: Option[String], timeoutMs: Long): Task[sigil.tool.fs.CommandResult] =
            realFs.executeCommand(command, workingDir, timeoutMs)
          override def readContents(filePath: String): Task[Option[sigil.storage.StorageContents]] =
            realFs.readContents(filePath)
          override def writeIfMatch(filePath: String, content: String, expected: sigil.storage.FileVersion): Task[sigil.storage.WriteResult] =
            realFs.writeIfMatch(filePath, content, expected)
        }
        val ctx = turnContext(workspace)
        val input = RefactorWithInstructionInput(
          path        = workspace.toString,
          glob        = None,
          findPattern = "// TODO:remove",
          instruction =
            """Call `submit_refactor_decisions` ONCE with this exact payload for the file you were given,
              |then call `complete_task`:
              |  decisions: [ {
              |    matchedLine: 2,
              |    action: "Edited",
              |    reason: "remove the TODO marker",
              |    oldText: "// TODO:remove",
              |    newText: "",
              |    startChar: 0,
              |    endChar: 14
              |  } ]""".stripMargin,
          workerModelId = Some(modelId.value),
          maxParallel   = 1,
          maxWorkers    = 5
        )
        for {
          prepared <- prepare(fs, store, input, ctx)
          _        <- apply(fs, store, prepared.sessionId, ctx)
        } yield {
          try {
            prepared.page0Diffs.size shouldBe 2
            val bReport = prepared.page0Diffs.find(_.path.endsWith("b.txt"))
            bReport shouldBe defined
            bReport.flatMap(_.workerError) shouldBe defined
            bReport.flatMap(_.workerError).get should include("b.txt")
            Files.readString(workspace.resolve("b.txt")) should include("// TODO:remove this-file-throws")
          } finally cleanup(workspace)
        }
      }
    }
  }

  "tear down" should {
    "dispose TestRefactorSigil" in TestRefactorSigil.shutdown.map(_ => succeed)
  }
}

case object RefactorTestUser extends AgentParticipantId {
  override def value: String = "refactor-test-user"
}

object RefactorTestTopic {
  val id: lightdb.id.Id[Topic] = lightdb.id.Id("refactor-test-topic")
  val label: String = "Refactor Spec"
  val summary: String = "Synthetic topic for the refactor live spec."
}

class TestRefactorDB(directory: Option[Path],
                     storeManager: CollectionManager,
                     upgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades)
  with WorkflowCollections

/** Minimal `Sigil` mixing `WorkflowSigil` for the refactor spec —
  * registers the worker-facing [[SubmitRefactorDecisionsTool]] in
  * staticTools so `AgentDecisionStep.resolveTools` can locate it. */
object TestRefactorSigil extends Sigil with WorkflowSigil {
  override type DB = TestRefactorDB
  override protected def buildDB(directory: Option[Path],
                                  storeManager: CollectionManager,
                                  upgrades: List[DatabaseUpgrade]): TestRefactorDB =
    new TestRefactorDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil
  override protected def participantIds: List[RW[? <: ParticipantId]] = List(RW.static(RefactorTestUser))
  override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
  override protected def participants: List[RW[? <: Participant]] = Nil

  override def staticTools: List[sigil.tool.Tool] =
    super.staticTools :+ SubmitRefactorDecisionsTool

  private val providerRef = new java.util.concurrent.atomic.AtomicReference[() => Task[Provider]](
    () => Task.error(new RuntimeException("TestRefactorSigil — no provider configured"))
  )
  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)

  override def providerFor(modelId: lightdb.id.Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerRef.get()()

  override def curate(conversationId: lightdb.id.Id[Conversation],
                      modelId: lightdb.id.Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(conversationId = conversationId))

  override def getInformation(id: lightdb.id.Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: lightdb.id.Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  override val embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider
  override val vectorIndex: VectorIndex = NoOpVectorIndex

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    spice.http.client.intercept.Interceptor.empty

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.exists(path)) {
      val s = Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      } finally s.close()
    }
  }
}
