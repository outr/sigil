package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.SpaceId
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{CodingWork, Complexity, ModelCandidate, ProviderStrategy}
import sigil.tool.fs.{GrepMatch, LocalFileSystemContext}
import sigil.tooling.refactor.{
  MatchAction, MatchDecision,
  RefactorSessionStore, RefactorWithInstructionInput,
  RefactorWithInstructionOutput, RefactorWithInstructionTool,
  RefactorWorkerDispatcher
}

import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * Acceptance coverage for sigil bug #224 — required `complexity`,
 * `ProviderStrategy`-driven worker model routing, `maxFiles`
 * cost cap, and the two-phase `confirmed` flow.
 *
 * Stays deterministic (no LLM) by injecting a
 * [[RefactorWorkerDispatcher]] that records the model id passed
 * in and returns precomputed decisions.
 */
class RefactorTwoPhaseSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 30.seconds

  /** Dispatcher that captures the model id it was handed plus the
    * files it was invoked for, and returns a fixed `Edited` decision
    * per match. Used to assert routing wired the expected model id
    * through to the worker call. */
  private final class RecordingDispatcher(startChar: Int,
                                          endChar: Int,
                                          oldText: String,
                                          newText: String) extends RefactorWorkerDispatcher {
    val seenModelIds: AtomicReference[List[String]]                = new AtomicReference(Nil)
    val seenFiles: AtomicReference[List[String]]                   = new AtomicReference(Nil)

    override def dispatch(ctx: TurnContext,
                          modelId: String,
                          filePath: String,
                          matches: List[GrepMatch],
                          instruction: String): Task[Either[String, List[MatchDecision]]] = Task {
      seenModelIds.updateAndGet(modelId :: _)
      seenFiles.updateAndGet(filePath :: _)
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
    val root = Files.createTempDirectory(s"refactor-two-phase-${rapid.Unique()}-").toAbsolutePath.normalize
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
    val convId = Conversation.id(s"refactor-two-phase-${rapid.Unique()}")
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
      currentToolInvokeId = Some(Event.id())
    )
  }

  "Refactor required complexity" should {

    // Acceptance #4: input without `complexity` fails round-trip.
    // Fabric round-trips a Json that's missing required fields by
    // throwing during `read`; we assert the error path explicitly.
    "fail to deserialize when complexity is missing" in Task {
      val noComplexity = fabric.obj(
        "path"        -> fabric.str("/tmp/anywhere"),
        "findPattern" -> fabric.str("pattern"),
        "instruction" -> fabric.str("do the thing")
      )
      val rw = summon[RW[RefactorWithInstructionInput]]
      val attempt = scala.util.Try(rw.write(noComplexity))
      attempt.isFailure shouldBe true
    }

    // Compile-time guard: maxFiles must be the field name. If the
    // suite compiles using `maxFiles =` and there's no `maxWorkers`
    // anywhere, the rename is complete. We also assert the new
    // default is 10000.
    "use maxFiles (not maxWorkers) and default to 10000" in Task {
      val input = RefactorWithInstructionInput(
        path        = "/tmp",
        findPattern = "x",
        instruction = "y",
        complexity  = Complexity.Low
      )
      input.maxFiles shouldBe 10000
      // Explicit override accepts the new name.
      val tighter = RefactorWithInstructionInput(
        path        = "/tmp",
        findPattern = "x",
        instruction = "y",
        complexity  = Complexity.Low,
        maxFiles    = 25
      )
      tighter.maxFiles shouldBe 25
    }
  }

  "Refactor two-phase confirm" should {

    // Acceptance #2: `confirmed = false` returns a Scope preview
    // without invoking the dispatcher; `confirmed = true` then
    // proceeds to dispatch and produce a Dispatched output.
    "return a Scope preview without dispatching when confirmed = false" in {
      val store     = new RefactorSessionStore()
      val workspace = materialize(List(
        "a.txt" -> "alpha\n// TODO:remove first\nbeta\n",
        "b.txt" -> "gamma\n// TODO:remove second\ndelta\n"
      ))
      val original = Map(
        "a.txt" -> Files.readString(workspace.resolve("a.txt")),
        "b.txt" -> Files.readString(workspace.resolve("b.txt"))
      )
      val fs  = new LocalFileSystemContext(basePath = Some(workspace))
      val ctx = turnContext()
      val dispatcher = new RecordingDispatcher(startChar = 0, endChar = 14, oldText = "// TODO:remove", newText = "")
      val tool = new RefactorWithInstructionTool(fs, store, workerDispatcher = Some(dispatcher))
      val previewInput = RefactorWithInstructionInput(
        path        = workspace.toString,
        glob        = None,
        findPattern = "// TODO:remove",
        instruction = "stub-driven",
        complexity  = Complexity.Low,
        maxParallel = 2,
        maxFiles    = 10,
        confirmed   = false
      )
      tool.invoke(previewInput, ctx).map {
        case scope: RefactorWithInstructionOutput.Scope =>
          try {
            scope.totalFiles shouldBe 2
            scope.totalMatches shouldBe 2
            scope.estimatedWorkerCallCount shouldBe 2
            scope.perFileMatchCounts.values.sum shouldBe 2
            scope.confirmCall should include("confirmed = true")
            scope.abortReason shouldBe None
            // No dispatcher invocation, no session staged, no file
            // touched.
            dispatcher.seenModelIds.get() shouldBe Nil
            dispatcher.seenFiles.get() shouldBe Nil
            store.size shouldBe 0
            Files.readString(workspace.resolve("a.txt")) shouldBe original("a.txt")
            Files.readString(workspace.resolve("b.txt")) shouldBe original("b.txt")
          } finally cleanup(workspace)
        case other =>
          cleanup(workspace)
          fail(s"expected Scope output for confirmed=false, got $other")
      }
    }

    "dispatch workers when confirmed = true after a Scope preview" in {
      val store     = new RefactorSessionStore()
      val workspace = materialize(List(
        "a.txt" -> "alpha\n// TODO:remove first\nbeta\n",
        "b.txt" -> "gamma\n// TODO:remove second\ndelta\n"
      ))
      val fs  = new LocalFileSystemContext(basePath = Some(workspace))
      val ctx = turnContext()
      val dispatcher = new RecordingDispatcher(startChar = 0, endChar = 14, oldText = "// TODO:remove", newText = "")
      val tool = new RefactorWithInstructionTool(fs, store, workerDispatcher = Some(dispatcher))
      val baseInput = RefactorWithInstructionInput(
        path        = workspace.toString,
        glob        = None,
        findPattern = "// TODO:remove",
        instruction = "stub-driven",
        complexity  = Complexity.Low,
        maxParallel = 2,
        maxFiles    = 10
      )
      for {
        previewOut <- tool.invoke(baseInput, ctx)
        confirmedOut <- tool.invoke(baseInput.copy(confirmed = true), ctx)
      } yield {
        try {
          previewOut shouldBe a [RefactorWithInstructionOutput.Scope]
          confirmedOut match {
            case d: RefactorWithInstructionOutput.Dispatched =>
              d.totalDiffs shouldBe 2
              d.page0Diffs.size shouldBe 2
              d.abortReason shouldBe None
              dispatcher.seenFiles.get().toSet.size shouldBe 2
              store.peek(d.sessionId) shouldBe defined
            case other => fail(s"expected Dispatched on confirm, got $other")
          }
        } finally cleanup(workspace)
      }
    }
  }

  "Refactor ProviderStrategy routing" should {

    // Acceptance #1: complexity = Low routes via
    // ProviderStrategy.candidates(CodingWork) filtered by
    // supportedComplexity. The resolved model id must come from the
    // strategy, NOT from ctx.routedModelId or the agent's default.
    //
    // We install a strategy with two candidates:
    //   - a Medium-only candidate (skipped at Low)
    //   - a Low+Medium candidate (picked at Low)
    // and assert the dispatcher saw the Low+Medium candidate's id.
    "pick the first CodingWork candidate whose supportedComplexity includes the input tier" in {
      val store      = new RefactorSessionStore()
      val workspace  = materialize(List("a.txt" -> "alpha\n// TODO:remove first\nbeta\n"))
      val fs         = new LocalFileSystemContext(basePath = Some(workspace))
      val ctx        = turnContext()
      val dispatcher = new RecordingDispatcher(startChar = 0, endChar = 14, oldText = "// TODO:remove", newText = "")
      val tool       = new RefactorWithInstructionTool(fs, store, workerDispatcher = Some(dispatcher))

      val mediumOnly = ModelCandidate(
        modelId             = Model.id("medium-only"),
        supportedComplexity = Set(Complexity.Medium)
      )
      val lowMedium = ModelCandidate(
        modelId             = Model.id("low-medium-coder"),
        supportedComplexity = Set(Complexity.Low, Complexity.Medium)
      )
      val strategy = ProviderStrategy.routed(
        default = List(mediumOnly, lowMedium),
        routes  = Map(CodingWork -> List(mediumOnly, lowMedium))
      )
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set[SpaceId](TestSpace)))
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))

      val input = RefactorWithInstructionInput(
        path        = workspace.toString,
        findPattern = "// TODO:remove",
        instruction = "stub-driven",
        complexity  = Complexity.Low,
        maxParallel = 1,
        maxFiles    = 5,
        confirmed   = true
      )

      tool.invoke(input, ctx).map { out =>
        try {
          out shouldBe a [RefactorWithInstructionOutput.Dispatched]
          dispatcher.seenModelIds.get().distinct shouldBe List("low-medium-coder")
          // The agent's TurnContext doesn't carry a routedModelId so we
          // can also verify the routing didn't accidentally fall back
          // to a non-strategy source.
          ctx.routedModelId shouldBe None
        } finally {
          TestSigil.reset()
          cleanup(workspace)
        }
      }
    }

    "report the resolved model id in the Scope preview" in {
      val store      = new RefactorSessionStore()
      val workspace  = materialize(List("a.txt" -> "alpha\n// TODO:remove first\nbeta\n"))
      val fs         = new LocalFileSystemContext(basePath = Some(workspace))
      val ctx        = turnContext()
      val dispatcher = new RecordingDispatcher(startChar = 0, endChar = 14, oldText = "// TODO:remove", newText = "")
      val tool       = new RefactorWithInstructionTool(fs, store, workerDispatcher = Some(dispatcher))
      val low        = ModelCandidate(
        modelId             = Model.id("low-tier-coder"),
        supportedComplexity = Set(Complexity.Low)
      )
      val strategy = ProviderStrategy.routed(
        default = List(low),
        routes  = Map(CodingWork -> List(low))
      )
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set[SpaceId](TestSpace)))
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))

      val input = RefactorWithInstructionInput(
        path        = workspace.toString,
        findPattern = "// TODO:remove",
        instruction = "stub-driven",
        complexity  = Complexity.Low,
        maxParallel = 1,
        maxFiles    = 5,
        confirmed   = false
      )
      tool.invoke(input, ctx).map {
        case scope: RefactorWithInstructionOutput.Scope =>
          try {
            scope.resolvedModelId shouldBe "low-tier-coder"
            scope.estimatedWorkerCallCount shouldBe 1
            // Preview must NOT dispatch.
            dispatcher.seenModelIds.get() shouldBe Nil
          } finally {
            TestSigil.reset()
            cleanup(workspace)
          }
        case other =>
          TestSigil.reset()
          cleanup(workspace)
          fail(s"expected Scope, got $other")
      }
    }
  }
}
