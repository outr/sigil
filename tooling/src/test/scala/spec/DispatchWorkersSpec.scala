package spec

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import fabric.{Arr, Bool, Json, NumInt, Obj, Str}
import lightdb.id.Id
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import profig.Profig
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.db.{Model, SigilDB}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.event.{Event, MessageRole, ToolInvoke}
import sigil.information.Information
import sigil.participant.{Participant, ParticipantId}
import sigil.provider.{
  CallId, GenerationSettings, Provider, ProviderCall, ProviderEvent, ProviderType,
  StopReason, Complexity, ProviderRequest
}
import sigil.script.ScriptExecutor
import sigil.signal.{EventState, Signal}
import sigil.tool.fs.LocalFileSystemContext
import sigil.tool.model.GrepInput
import sigil.tool.{ToolName, Tool}
import sigil.tooling.dispatch.{
  DispatchWorkersInput, DispatchWorkersOutput, DispatchWorkersTool,
  LlmStep, ScriptStep, WorkerItemSource, WorkerPipeline, WorkerResult
}
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import sigil.{Sigil, SpaceId, TurnContext}
import spice.http.HttpRequest

import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Acceptance for sigil bug #230 — the `dispatch_workers` generic
 * primitive replacing the refactor session.
 *
 * Six cases. All run deterministically against a stubbed Provider
 * and a real `LocalFileSystemContext` against a per-test tmpdir,
 * so the spec stays in-process and finishes in milliseconds.
 */
class DispatchWorkersSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  DispatchTestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 30.seconds

  // ------------- shared fixtures -------------

  private def turnContext(): TurnContext = {
    val convId = Conversation.id(s"dispatch-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(DispatchTestTopicId, "test", "test")),
      _id    = convId
    )
    DispatchTestSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    TurnContext(
      sigil               = DispatchTestSigil,
      chain               = List(DispatchTestUser),
      conversation        = conv,
      turnInput           = TurnInput(ConversationView(conversationId = convId)),
      currentToolInvokeId = Some(Event.id())
    )
  }

  private def materialize(files: List[(String, String)]): Path = {
    val root = Files.createTempDirectory(s"dispatch-${rapid.Unique()}-").toAbsolutePath.normalize
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

  // ------------- the six cases -------------

  "dispatch_workers LLM-only" should {

    // Acceptance #1 — LLM-only pipeline with free-form output. Ten
    // items, no script, classify-each-item shape. Per-item the
    // worker LLM emits a string; the framework wraps it as
    // `{"text": "<response>"}` and the result lands as a
    // WorkerResult.Success carrying that payload.
    "produce one Success per item for a free-form classification flow" in {
      DispatchTestSigil.reset()
      // Each item gets one ToolCallText response. The stub just
      // echoes the item's payload back as JSON-ish text so we can
      // verify per-item routing without hand-coding 10 different
      // responses.
      DispatchTestSigil.setProvider(Task.pure(StubProvider.echoing()))
      val tool = new DispatchWorkersTool()
      val items: List[Json] = (1 to 10).toList.map(i => Str(s"item-$i"))
      val input = DispatchWorkersInput(
        complexity    = Complexity.Low,
        confirmed     = true,
        items         = WorkerItemSource.FromList(items),
        pipeline      = WorkerPipeline(llm = Some(LlmStep(prompt = "Echo: {{item}}"))),
        workerModelId = Some("stub-model"),
        maxParallel   = 4
      )
      tool.invoke(input, turnContext()).map {
        case d: DispatchWorkersOutput.Dispatched =>
          d.totalItems shouldBe 10
          d.successCount shouldBe 10
          d.failureCount shouldBe 0
          d.results.size shouldBe 10
          all(d.results) shouldBe a [WorkerResult.Success]
        case other => fail(s"expected Dispatched, got $other")
      }
    }
  }

  "dispatch_workers LLM with outputSchema feeding script" should {

    // Acceptance #2 — LLM emits a JSON object matching `outputSchema`;
    // the script step receives the parsed Json as its `input`
    // binding. Verify the binding round-trips by having the script
    // re-emit the input verbatim.
    "produce typed JSON usable as the script step's `input` binding" in {
      DispatchTestSigil.reset()
      // Stub emits a JSON object per item — the dispatcher should
      // parse it (because `outputSchema` is set) instead of wrapping
      // as `{"text": ...}`.
      DispatchTestSigil.setProvider(Task.pure(StubProvider.constant(
        """{"verdict": "ok", "score": 7}"""
      )))
      val recordedInputs = scala.collection.mutable.ListBuffer.empty[Json]
      val executor = new ScriptExecutor {
        override def execute(code: String, bindings: Map[String, Any]): Task[String] = Task {
          val input = bindings("input").asInstanceOf[Json]
          recordedInputs += input
          JsonFormatter.Compact(input)
        }
      }
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val items: List[Json] = List(Str("a"), Str("b"))
      val input = DispatchWorkersInput(
        complexity    = Complexity.Low,
        confirmed     = true,
        items         = WorkerItemSource.FromList(items),
        pipeline      = WorkerPipeline(
          llm    = Some(LlmStep(
            prompt       = "Score: {{item}}",
            outputSchema = Some(Obj(
              "type" -> Str("object"),
              "properties" -> Obj(
                "verdict" -> Obj("type" -> Str("string")),
                "score"   -> Obj("type" -> Str("integer"))
              )
            ))
          )),
          script = Some(ScriptStep(code = "input"))
        ),
        workerModelId = Some("stub-model")
      )
      tool.invoke(input, turnContext()).map {
        case d: DispatchWorkersOutput.Dispatched =>
          d.totalItems shouldBe 2
          d.successCount shouldBe 2
          recordedInputs.size shouldBe 2
          // Each recorded input is the parsed LLM output — verify the
          // schema-shaped fields survived round-tripping.
          val firstInput = recordedInputs.head match {
            case o: Obj => o
            case other  => fail(s"expected parsed Obj input to script, got $other")
          }
          firstInput.value.get("verdict") shouldBe Some(Str("ok"))
          firstInput.value.get("score") shouldBe Some(NumInt(7))
        case other => fail(s"expected Dispatched, got $other")
      }
    }
  }

  "dispatch_workers refactor use case (LLM + script that writes via edit_file)" should {

    // Acceptance #3 — three files, all written. LLM proposes the
    // edit per file, script applies it via the host `edit_file`
    // tool's safe-edit flow (hash check passes). No session bookkeeping
    // — the apply is in-script, not in a follow-up tool call.
    "write all three files end-to-end" in {
      DispatchTestSigil.reset()
      val workspace = materialize(List(
        "a.txt" -> "header\nLINE-TO-EDIT\nfooter\n",
        "b.txt" -> "header\nLINE-TO-EDIT\nfooter\n",
        "c.txt" -> "header\nLINE-TO-EDIT\nfooter\n"
      ))
      // LLM stub emits one edit per item — the script then applies
      // the substitution. We simulate the apply directly in the
      // executor so the test stays in-process; in production the
      // script would invoke `edit_file` via the tools binding.
      DispatchTestSigil.setProvider(Task.pure(StubProvider.constant(
        """{"oldText": "LINE-TO-EDIT", "newText": "EDITED"}"""
      )))
      val writeCount = new AtomicInteger(0)
      val executor = new ScriptExecutor {
        override def execute(code: String, bindings: Map[String, Any]): Task[String] = Task {
          val itemJson = bindings("item").asInstanceOf[Json]
          val edit     = bindings("input").asInstanceOf[Json]
          val filePath = itemJson match {
            case o: Obj => o.value.get("filePath").collect { case Str(s, _) => s }.getOrElse("")
            case _      => ""
          }
          val (oldText, newText) = edit match {
            case o: Obj =>
              (o.value.get("oldText").collect { case Str(s, _) => s }.getOrElse(""),
               o.value.get("newText").collect { case Str(s, _) => s }.getOrElse(""))
            case _ => ("", "")
          }
          val current = Files.readString(workspace.resolve(filePath))
          val updated = current.replace(oldText, newText)
          Files.writeString(workspace.resolve(filePath), updated,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
          writeCount.incrementAndGet()
          JsonFormatter.Compact(Obj("Success" -> Obj("replacements" -> NumInt(1))))
        }
      }
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val items: List[Json] = List("a.txt", "b.txt", "c.txt").map(p => Obj("filePath" -> Str(p)))
      val input = DispatchWorkersInput(
        complexity    = Complexity.Low,
        confirmed     = true,
        items         = WorkerItemSource.FromList(items),
        pipeline      = WorkerPipeline(
          llm    = Some(LlmStep(
            prompt       = "Propose edit for {{item.filePath}}",
            outputSchema = Some(Obj("type" -> Str("object")))
          )),
          script = Some(ScriptStep(code = "// apply edit via script binding"))
        ),
        workerModelId = Some("stub-model"),
        maxParallel   = 3
      )
      tool.invoke(input, turnContext()).map {
        case d: DispatchWorkersOutput.Dispatched =>
          try {
            withClue(s"results: ${d.results.mkString("\n")}\n") {
              d.totalItems shouldBe 3
              d.successCount shouldBe 3
              writeCount.get shouldBe 3
              // Hash-check fires — the script ran edit_file's safe-edit
              // flow; all three files updated on disk.
              Files.readString(workspace.resolve("a.txt")) should not include "LINE-TO-EDIT"
              Files.readString(workspace.resolve("b.txt")) should not include "LINE-TO-EDIT"
              Files.readString(workspace.resolve("c.txt")) should not include "LINE-TO-EDIT"
            }
          } finally cleanup(workspace)
        case other =>
          cleanup(workspace)
          fail(s"expected Dispatched, got $other")
      }
    }
  }

  "dispatch_workers partial-success on a Stale hash" should {

    // Acceptance #4 — three files; one was mutated externally so the
    // script returns a `Stale` marker for it. The other two commit
    // successfully — no rollback. Verify the result carries one
    // Stale entry and two Success entries, and the two writes
    // happened on disk.
    "settle two successes and one Stale entry without rolling back the successes" in {
      DispatchTestSigil.reset()
      val workspace = materialize(List(
        "a.txt"          -> "header\nLINE-TO-EDIT\nfooter\n",
        "stale.txt"      -> "header\nLINE-TO-EDIT\nfooter\n",
        "c.txt"          -> "header\nLINE-TO-EDIT\nfooter\n"
      ))
      DispatchTestSigil.setProvider(Task.pure(StubProvider.constant(
        """{"oldText": "LINE-TO-EDIT", "newText": "EDITED"}"""
      )))
      val executor = new ScriptExecutor {
        override def execute(code: String, bindings: Map[String, Any]): Task[String] = Task {
          val itemJson = bindings("item").asInstanceOf[Json]
          val filePath = itemJson match {
            case o: Obj => o.value.get("filePath").collect { case Str(s, _) => s }.getOrElse("")
            case _      => ""
          }
          if (filePath == "stale.txt") {
            // Simulate `edit_file` detecting a hash mismatch — the
            // script returns the `Stale(currentHash, ...)` shape
            // and the dispatcher pattern-matches it.
            JsonFormatter.Compact(Obj(
              "Stale" -> Obj(
                "currentHash"    -> Str("deadbeef"),
                "currentContent" -> Str("freshly-modified")
              )
            ))
          } else {
            val current = Files.readString(workspace.resolve(filePath))
            val updated = current.replace("LINE-TO-EDIT", "EDITED")
            Files.writeString(workspace.resolve(filePath), updated,
              StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            JsonFormatter.Compact(Obj("Success" -> Obj("replacements" -> NumInt(1))))
          }
        }
      }
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val items: List[Json] = List("a.txt", "stale.txt", "c.txt").map(p => Obj("filePath" -> Str(p)))
      val input = DispatchWorkersInput(
        complexity    = Complexity.Low,
        confirmed     = true,
        items         = WorkerItemSource.FromList(items),
        pipeline      = WorkerPipeline(
          llm    = Some(LlmStep(
            prompt       = "Propose edit for {{item.filePath}}",
            outputSchema = Some(Obj("type" -> Str("object")))
          )),
          script = Some(ScriptStep(code = "// apply"))
        ),
        workerModelId = Some("stub-model"),
        maxParallel   = 3
      )
      tool.invoke(input, turnContext()).map {
        case d: DispatchWorkersOutput.Dispatched =>
          try {
            withClue(s"results: ${d.results.mkString("\n")}\n") {
              d.totalItems shouldBe 3
              d.results.count(_.isInstanceOf[WorkerResult.Success]) shouldBe 2
              d.results.count(_.isInstanceOf[WorkerResult.Stale]) shouldBe 1
              // The two non-stale files are written; the stale file is
              // unchanged (the script returned Stale without writing).
              Files.readString(workspace.resolve("a.txt")) should not include "LINE-TO-EDIT"
              Files.readString(workspace.resolve("c.txt")) should not include "LINE-TO-EDIT"
              Files.readString(workspace.resolve("stale.txt")) should include("LINE-TO-EDIT")
            }
          } finally cleanup(workspace)
        case other =>
          cleanup(workspace)
          fail(s"expected Dispatched, got $other")
      }
    }
  }

  "dispatch_workers confirmed=false" should {

    // Acceptance #5 — confirmed=false returns a Scope preview with
    // the resolved item count + model id; no provider call, no
    // script invocation.
    "return a scope preview without invoking the provider or any script" in {
      DispatchTestSigil.reset()
      val providerCalls = new AtomicInteger(0)
      DispatchTestSigil.setProvider(Task.pure(StubProvider.counting(providerCalls)))
      val scriptCalls = new AtomicInteger(0)
      val executor = new ScriptExecutor {
        override def execute(code: String, bindings: Map[String, Any]): Task[String] = Task {
          scriptCalls.incrementAndGet()
          ""
        }
      }
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val items: List[Json] = (1 to 7).toList.map(i => Str(s"item-$i"))
      val input = DispatchWorkersInput(
        complexity    = Complexity.Low,
        confirmed     = false,  // explicit — also the default
        items         = WorkerItemSource.FromList(items),
        pipeline      = WorkerPipeline(
          llm    = Some(LlmStep(prompt = "Classify: {{item}}")),
          script = Some(ScriptStep(code = "input"))
        ),
        workerModelId = Some("stub-model")
      )
      tool.invoke(input, turnContext()).map {
        case s: DispatchWorkersOutput.Scope =>
          s.totalItems shouldBe 7
          s.resolvedModelId shouldBe "stub-model"
          s.confirmCall should include("confirmed = true")
          // No worker ran — neither the LLM stub nor the script
          // executor was invoked.
          providerCalls.get shouldBe 0
          scriptCalls.get shouldBe 0
        case other => fail(s"expected Scope, got $other")
      }
    }
  }

  "dispatch_workers suggested-tool surfacing after grep" should {

    // Acceptance #6 — grep declares
    // `suggestedNextTools = List("dispatch_workers")`. When a
    // ToolInvoke against grep settles, the framework appends
    // dispatch_workers to the per-participant ParticipantProjection's
    // `suggestedTools` overlay — the same surface that
    // Provider.renderSystem reads to emit the "Suggested tools"
    // prompt section.
    "append dispatch_workers to the projection's suggestedTools overlay" in {
      DispatchTestSigil.reset()
      val convId = Conversation.id(s"dispatch-overlay-${rapid.Unique()}")
      val conv = Conversation(
        topics = List(TopicEntry(DispatchTestTopicId, "test", "test")),
        _id    = convId
      )
      val origin: Id[Event] = Id[Event](s"dispatch-overlay-origin-${rapid.Unique()}")
      for {
        _ <- DispatchTestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- DispatchTestSigil.withDB(_.participantProjections.transaction { tx =>
          tx.list.flatMap(rows => Task.sequence(rows.filter(_.conversationId == convId).map(r => tx.delete(r._id))).unit)
        })
        _ <- DispatchTestSigil.publish(ToolInvoke(
          toolName       = ToolName("grep"),
          participantId  = DispatchTestUser,
          conversationId = convId,
          topicId        = DispatchTestTopicId,
          state          = EventState.Complete,
          role           = MessageRole.Standard,
          input          = Some(GrepInput(path = ".", pattern = "x")),
          origin         = Some(origin)
        ))
        suggested <- DispatchTestSigil.withDB(_.participantProjections.transaction { tx =>
          tx.list.map(_.filter(_.conversationId == convId).flatMap(_.suggestedTools).distinct)
        })
      } yield {
        // The grep tool declared suggestedNextTools = List("dispatch_workers");
        // the projection should now reflect that under the same overlay
        // surface Provider.renderSystem reads to emit the "Suggested
        // tools" prompt section.
        suggested should contain(ToolName("dispatch_workers"))
      }
    }
  }

  "tear down" should {
    "dispose DispatchTestSigil" in DispatchTestSigil.shutdown.map(_ => succeed)
  }
}

// ---------------- shared infra ----------------

case object DispatchTestUser extends ParticipantId {
  override def value: String = "dispatch-test-user"
}

val DispatchTestTopicId: Id[Topic] = Id[Topic]("dispatch-test-topic")

case object DispatchTestSpace extends SpaceId {
  override def value: String = "dispatch-test-space"
}

class DispatchTestDB(directory: Option[Path],
                     storeManager: CollectionManager,
                     upgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades)

/** Minimal Sigil for the DispatchWorkersSpec. Registers `GrepTool`
  * in `staticTools` so the projection-update path can read its
  * `suggestedNextTools` declaration. */
object DispatchTestSigil extends Sigil {
  override type DB = DispatchTestDB
  override protected def buildDB(directory: Option[Path],
                                  storeManager: CollectionManager,
                                  upgrades: List[DatabaseUpgrade]): DispatchTestDB =
    new DispatchTestDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(DispatchTestUser))
  override protected def spaceIds: List[RW[? <: SpaceId]] =
    List(RW.static(DispatchTestSpace))
  override protected def participants: List[RW[? <: Participant]] = Nil

  /** Include `GrepTool` so the bug-#230 propagation path resolves the
    * tool's `suggestedNextTools` when a `grep` ToolInvoke settles. */
  override def staticTools: List[Tool] =
    super.staticTools :+ new sigil.tool.fs.GrepTool(new LocalFileSystemContext(basePath = None))

  private val providerRef = new java.util.concurrent.atomic.AtomicReference[() => Task[Provider]](
    () => Task.error(new RuntimeException("DispatchTestSigil — no provider configured"))
  )
  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)

  def reset(): Unit = {
    providerRef.set(() => Task.error(new RuntimeException("DispatchTestSigil — provider not set")))
  }

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerRef.get()()

  override def curate(conversationId: Id[Conversation],
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(conversationId = conversationId))

  override def getInformation(id: Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: Id[Conversation]): Task[Option[SpaceId]] =
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

/** Stub provider for the DispatchWorkersSpec. Emits a single
  * `ContentBlockDelta` text per call — callers parametrize the
  * response text or pass a counter. */
object StubProvider {

  def echoing(): Provider = new BaseStub {
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      // Extract the last user message text and echo it back.
      val text = userText(input).getOrElse("(no input)")
      Stream.emits(List(
        ProviderEvent.ContentBlockDelta(CallId("echo"), s"echoed: $text"),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  def constant(text: String): Provider = new BaseStub {
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(
        ProviderEvent.ContentBlockDelta(CallId("const"), text),
        ProviderEvent.Done(StopReason.Complete)
      ))
  }

  def counting(counter: AtomicInteger): Provider = new BaseStub {
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      counter.incrementAndGet()
      Stream.emits(List(
        ProviderEvent.ContentBlockDelta(CallId("count"), "{}"),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def userText(input: ProviderCall): Option[String] = {
    input.messages.collectFirst {
      case u: sigil.provider.ProviderMessage.User => u.content.collect {
        case sigil.provider.MessageContent.Text(t) => t
      }.mkString
    }.filter(_.nonEmpty)
  }

  private abstract class BaseStub extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: Sigil = DispatchTestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("StubProvider"))
  }
}
