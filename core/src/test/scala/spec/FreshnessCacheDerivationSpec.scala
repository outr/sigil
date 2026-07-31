package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId,
  ConversationMode,
  ConversationRequest,
  GenerationSettings,
  Instructions,
  Provider,
  ProviderCall,
  ProviderEvent,
  ProviderType,
  StopReason
}
import sigil.tool.{
  CachedToolRead,
  DiscoverySpec,
  Effect,
  Freshness,
  MutationTarget,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolContext,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolSpec
}
import spice.http.HttpRequest

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * The turn-scoped read cache derives from each tool's declared
 * Effect/Freshness instead of a boolean:
 *
 *   - `ReadOnly(Pure)` — cached for the whole turn.
 *   - `ReadOnly(Stable)` — cached, but INVALIDATED when a mutating
 *     call lands (conservatively when either side declares no
 *     target), so `read_file(x) → edit_file(x) → read_file(x)`
 *     returns post-edit content.
 *   - `ReadOnly(Volatile)` — never cached.
 */
class FreshnessCacheDerivationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "freshness-model")
  TestSigil.testModel(modelId)

  case class FreshPingInput() extends ToolInput derives RW
  ToolInput.register(RW.static(FreshPingInput()))

  /**
   * Read tool with a declared freshness; returns the live value of a
   * shared mutable cell so a cached serve is distinguishable from a
   * re-execution.
   */
  private class FreshReadTool(val name0: ToolName, freshness0: Freshness, cell: AtomicReference[String], val counter: AtomicInteger)
    extends Tool {
    type Input = FreshPingInput
    type Output = TextToolOutput
    val io: ToolIO[FreshPingInput, TextToolOutput] = ToolIO.derived[FreshPingInput, TextToolOutput]
    val spec: ToolSpec = ToolSpec(
      name = name0,
      description = "freshness-declared read fixture",
      profile = ToolProfile(effect = Effect.ReadOnly(freshness0)),
      discovery = DiscoverySpec(keywords = Set("test", "freshness"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

    private def executeOutput(input: FreshPingInput, ctx: ToolContext): Task[TextToolOutput] = Task {
      counter.incrementAndGet()
      TextToolOutput(cell.get())
    }
  }

  /**
   * Mutating tool writing the shared cell; declares no target, so
   * invalidation is conservative (whole Stable cache).
   */
  private class CellEditTool(val name0: ToolName, cell: AtomicReference[String]) extends Tool {
    type Input = FreshPingInput
    type Output = TextToolOutput
    val io: ToolIO[FreshPingInput, TextToolOutput] = ToolIO.derived[FreshPingInput, TextToolOutput]
    val spec: ToolSpec = ToolSpec(
      name = name0,
      description = "mutating fixture writing the shared cell",
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "mutate"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

    private def executeOutput(input: FreshPingInput, ctx: ToolContext): Task[TextToolOutput] = Task {
      cell.set("post-edit")
      TextToolOutput("edited")
    }
  }

  private class OneCallProvider(tool: Tool { type Input = FreshPingInput }) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(s"c-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, tool.name.value),
        ProviderEvent.toolCall(cid, tool)(FreshPingInput()),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def requestFor(convId: Id[Conversation],
                         tools: Vector[Tool],
                         cacheRef: AtomicReference[Map[String, CachedToolRead]]): ConversationRequest =
    ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools = tools,
      chain = List(TestUser, TestAgent),
      toolResultCacheRef = cacheRef
    )

  private def newConv(prefix: String): Task[Id[Conversation]] = {
    val convId = Conversation.id(s"$prefix-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_ => convId)
  }

  "Freshness-derived read cache" should {

    "serve a Pure read from cache across iterations, surviving an unrelated mutation" in {
      val cell = new AtomicReference("original")
      val counter = new AtomicInteger(0)
      val readTool = new FreshReadTool(ToolName("pure_read"), Freshness.Pure, cell, counter)
      val editTool = new CellEditTool(ToolName("pure_epoch_edit"), cell)
      val cacheRef = new AtomicReference(Map.empty[String, CachedToolRead])
      for {
        convId <- newConv("pure")
        tools = Vector[Tool](readTool, editTool)
        _ <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(readTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
        _ <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(editTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
        _ <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(readTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
      } yield counter.get() shouldBe 1
    }

    "invalidate a Stable read when a mutation lands — read(x) → edit(x) → read(x) returns post-edit content" in {
      val cell = new AtomicReference("original")
      val counter = new AtomicInteger(0)
      val readTool = new FreshReadTool(ToolName("stable_read"), Freshness.Stable, cell, counter)
      val editTool = new CellEditTool(ToolName("stable_cell_edit"), cell)
      val cacheRef = new AtomicReference(Map.empty[String, CachedToolRead])
      for {
        convId <- newConv("stable")
        tools = Vector[Tool](readTool, editTool)
        _ <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(readTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
        // Identical read served from cache pre-mutation.
        _ <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(readTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
        _ <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(editTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
        signals <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(readTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
      } yield {
        counter.get() shouldBe 2 // 1st executed, 2nd cached, post-edit re-executed
        val rendered = signals.collect {
          case td: sigil.signal.ToolDelta => td.output.collect { case t: TextToolOutput => t.text }
        }.flatten
        rendered should contain("post-edit")
      }
    }

    "never cache a Volatile read" in {
      val cell = new AtomicReference("live")
      val counter = new AtomicInteger(0)
      val readTool = new FreshReadTool(ToolName("volatile_read"), Freshness.Volatile, cell, counter)
      val cacheRef = new AtomicReference(Map.empty[String, CachedToolRead])
      for {
        convId <- newConv("volatile")
        tools = Vector[Tool](readTool)
        _ <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(readTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
        _ <- Orchestrator.process(
          TestSigil,
          new OneCallProvider(readTool),
          requestFor(convId, tools, cacheRef),
          Conversation(topics = TestTopicStack, _id = convId)).toList
      } yield {
        counter.get() shouldBe 2
        cacheRef.get() shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
