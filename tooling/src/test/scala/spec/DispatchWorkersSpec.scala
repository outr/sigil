package spec

import fabric.rw.*
import fabric.{Json, NumInt, Obj, Str}
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
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.script.ScalaScriptExecutor
import sigil.signal.{EventState, Signal}
import sigil.tool.fs.LocalFileSystemContext
import sigil.tool.model.GrepInput
import sigil.tool.{Tool, ToolName}
import sigil.tooling.container.{CreateContainerInput, CreateContainerTool}
import sigil.tooling.dispatch.{DispatchWorkersInput, DispatchWorkersOutput, DispatchWorkersTool}
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import sigil.{Sigil, SpaceId, TurnContext}
import spice.http.HttpRequest

import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Acceptance for the `dispatch_workers` generic primitive — the
 * adhoc-`action` shape (per bug #245). Each worker runs a shared,
 * once-compiled Scala action over a group of items.
 *
 * Runs deterministically against a real `ScalaScriptExecutor` and a
 * per-test tmpdir, so the spec stays in-process.
 */
class DispatchWorkersSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  DispatchTestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 60.seconds

  private val executor = new ScalaScriptExecutor()

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

  /** Persist `items` as a fresh container under `ctx`'s conversation
    * and return the resulting `itemsId`. */
  private def containerFor(items: List[Json], ctx: TurnContext): lightdb.id.Id[sigil.tool.output.ToolOutputNode] =
    CreateContainerTool.invoke(CreateContainerInput(items), ctx).sync().itemsId

  // ------------- the cases -------------

  "dispatch_workers action" should {

    // One outcome per item, the action's trailing expression captured
    // as that worker's Right value.
    "produce one Right outcome per item when the action transforms each item" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 10).toList.map(i => Str(s"item-$i"))
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "fabric.Str(items.head.asString.toUpperCase)",
        confirmed = true,
        maxParallel = 4
      )
      tool.invoke(input, ctx).map {
        case d: DispatchWorkersOutput.DispatchResult =>
          d.totalItems shouldBe 10
          d.successCount shouldBe 10
          d.failureCount shouldBe 0
          d.perItem.size shouldBe 10
          all(d.perItem.map(_.result.isRight)) shouldBe true
          d.perItem.head.result shouldBe Right(Str("ITEM-1"))
        case other => fail(s"expected DispatchResult, got $other")
      }
    }
  }

  "dispatch_workers groupSize" should {

    // With groupSize=3 over 7 items, the action runs over 3 groups:
    // [3, 3, 1]. Each worker sees a `items` list of the group's
    // payloads; the action reports the group's size.
    "batch items into one worker invocation per group of groupSize" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 7).toList.map(i => Str(s"item-$i"))
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "fabric.NumInt(items.size.toLong)",
        groupSize = 3,
        confirmed = true
      )
      tool.invoke(input, ctx).map {
        case d: DispatchWorkersOutput.DispatchResult =>
          d.totalItems shouldBe 7
          d.successCount shouldBe 3
          d.perItem.map(_.result) shouldBe List(
            Right(NumInt(3)), Right(NumInt(3)), Right(NumInt(1))
          )
        case other => fail(s"expected DispatchResult, got $other")
      }
    }
  }

  "dispatch_workers file-write action" should {

    // The action writes each file to disk — three items, three
    // writes, verified on disk. Mirrors a refactor flow where the
    // action is the apply step.
    "write all three files end-to-end" in {
      DispatchTestSigil.reset()
      val workspace = materialize(List(
        "a.txt" -> "header\nLINE-TO-EDIT\nfooter\n",
        "b.txt" -> "header\nLINE-TO-EDIT\nfooter\n",
        "c.txt" -> "header\nLINE-TO-EDIT\nfooter\n"
      ))
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = List("a.txt", "b.txt", "c.txt")
        .map(p => Obj("filePath" -> Str(workspace.resolve(p).toString)))
      val action =
        """val path = java.nio.file.Path.of(items.head("filePath").asString)
          |val updated = java.nio.file.Files.readString(path).replace("LINE-TO-EDIT", "EDITED")
          |java.nio.file.Files.writeString(path, updated)
          |fabric.Str("written")""".stripMargin
      val input = DispatchWorkersInput(
        itemsId     = containerFor(items, ctx),
        action      = action,
        confirmed   = true,
        maxParallel = 3
      )
      tool.invoke(input, ctx).map {
        case d: DispatchWorkersOutput.DispatchResult =>
          try {
            withClue(s"outcomes: ${d.perItem.mkString("\n")}\n") {
              d.totalItems shouldBe 3
              d.successCount shouldBe 3
              Files.readString(workspace.resolve("a.txt")) should not include "LINE-TO-EDIT"
              Files.readString(workspace.resolve("b.txt")) should not include "LINE-TO-EDIT"
              Files.readString(workspace.resolve("c.txt")) should not include "LINE-TO-EDIT"
            }
          } finally cleanup(workspace)
        case other =>
          cleanup(workspace)
          fail(s"expected DispatchResult, got $other")
      }
    }
  }

  "dispatch_workers suggested-tool surfacing after grep" should {

    // grep declares `suggestedNextTools = List("dispatch_workers")`.
    // When a ToolInvoke against grep settles, the framework appends
    // dispatch_workers to the per-participant ParticipantProjection's
    // `suggestedTools` overlay.
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

  /** Include `GrepTool` so the suggested-tool propagation path
    * resolves the tool's `suggestedNextTools` when a `grep`
    * ToolInvoke settles. */
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

/** Stub provider for the dispatch specs. Emits a single
  * `ContentBlockDelta` text per call — callers parametrize the
  * response text or pass a counter. */
object StubProvider {

  def echoing(): Provider = new BaseStub {
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
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
