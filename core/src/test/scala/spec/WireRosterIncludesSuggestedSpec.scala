package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ParticipantProjection}
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{CapabilityResults, Event, Message}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolPolicy
}
import sigil.signal.{EventState, Signal}
import sigil.tool.{InMemoryToolFinder, TextToolOutput, Tool, ToolContext, ToolInput, ToolName}
import sigil.tool.core.{ChangeModeTool, CoreTools, FindCapabilityTool, RecordConsentTool, RespondOptionsTool, RespondTool}
import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Sigil #305 — when the provider's pre-flight gate detects the request
 * exceeds the model's context budget, `emergencyShed` strips the wire
 * tool roster down to a hardcoded 9-name "essentials" set BEFORE
 * dropping any messages. That obliterates `projection.suggestedTools`
 * (the agent's just-discovered action tools), `agent.toolNames`
 * baseline beyond the essentials, and `conversationToolOverlays` extras
 * from `start_metals` etc. The system prompt's "Suggested tools"
 * section still advertises the discovered names; the wire `tools` JSON
 * carries 4 tools the agent can't use to advance — change_mode loop.
 *
 * Field evidence — Sage wire log 2026-05-28 (same conversation, ~3 min
 * apart, same DB, no policy / overlay mutations): L259 = 54 tools,
 * L395 = 4 tools = exactly the emergencyShed essentials set.
 *
 * The test registers a model with a tiny context length so the
 * emergency-shed path fires deterministically, seeds
 * `projection.suggestedTools` with the discovered names, and asserts
 * those names land on the wire alongside the essentials.
 */
class WireRosterIncludesSuggestedSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Tiny context — guarantees emergencyShed kicks in even on a small
  // synthetic conversation. The actual Sage failure happened around
  // 32K tokens; we squeeze the budget here so the same path triggers
  // with bounded test content.
  private val modelId: Id[Model] = Model.id("test", "wire-roster-tiny")
  private val tinyModel: Model = {
    val now = lightdb.time.Timestamp()
    Model(
      canonicalSlug = modelId.value,
      huggingFaceId = "",
      name = modelId.value,
      description = "Synthetic tiny-context model for sigil #305 emergencyShed coverage.",
      contextLength = 6500L,
      architecture = ModelArchitecture(
        modality = "text->text",
        inputModalities = List("text"),
        outputModalities = List("text"),
        tokenizer = "GPT",
        instructType = None
      ),
      pricing = ModelPricing(
        prompt = BigDecimal(0),
        completion = BigDecimal(0),
        webSearch = None,
        inputCacheRead = None),
      topProvider = ModelTopProvider(
        contextLength = Some(6500L),
        maxCompletionTokens = Some(64L),
        isModerated = false),
      perRequestLimits = None,
      supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
      defaultParameters = ModelDefaultParameters(),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = ""),
      created = now,
      modified = now,
      _id = modelId
    )
  }
  TestSigil.cache.merge(List(tinyModel)).sync()

  // ---- synthetic tools the test catalog resolves by name ----

  case class StubInput(arg: String = "") extends ToolInput derives RW

  /**
   * Verbose stub tool — long description simulates real LSP/BSP tool
   * schemas that contribute hundreds of tokens each to the wire roster.
   * 40+ instances of this drive the request over the budget so
   * emergencyShed actually fires.
   */
  private def stubTool(n: String): Tool = new Tool {
    type Input = StubInput
    type Output = TextToolOutput
    val inputRW = summon[RW[StubInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName(n)
    val description =
      s"Tool $n with a deliberately verbose description to simulate the per-tool token cost of " +
        "real LSP/BSP/Metals tools in production. Each tool contributes roughly 60-80 tokens of " +
        "schema (name, description, parameter envelope). With 30+ such tools in the wire roster, " +
        "the total tool catalog dominates the request's token estimate, which is exactly the " +
        "configuration the emergency-shed path was designed to address. The fix under test " +
        "must preserve the agent's discovered tools (grep, dispatch_workers) while shedding the " +
        "unused bulk."
    override def executeOutput(input: StubInput, ctx: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput("ok"))
  }

  private val GrepTool: Tool = stubTool("grep")
  private val DispatchWorkersTool: Tool = stubTool("dispatch_workers")

  // A pile of stub tools simulating the start_metals overlay's
  // lsp/bsp/metals tool set. Inflates the wire roster so the
  // emergency-shed path triggers.
  private val BulkOverlayTools: List[Tool] = (1 to 40).toList.map(i => stubTool(s"bulk_tool_$i"))

  // ---- capturing provider — records every translated ProviderCall ----

  final private class CapturingProvider(
    captured: ConcurrentLinkedQueue[ProviderCall]
  ) extends Provider {
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      captured.add(input)
      val cid = CallId("clean-respond")
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          cid,
          _root_.sigil.tool.model.RespondInput(
            topicLabel = "T",
            topicSummary = "test",
            content = "ok",
            endsTurn = true
          )
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def installFinder(): Unit = {
    val tools = CoreTools.all.toList ++
      List(ChangeModeTool, GrepTool, DispatchWorkersTool) ++
      BulkOverlayTools
    TestSigil.setToolFinder(InMemoryToolFinder(tools.distinctBy(_.name)))
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      // Baseline includes the bulk overlay simulating start_metals's
      // Active(...) policy injecting 40 LSP/BSP tools. With these in
      // the wire roster, the request crosses the model's 6500-token
      // budget — exactly the condition Sage hit at L395.
      modelId = modelId,
      toolNames = List(
        RespondTool.schema.name,
        RespondOptionsTool.schema.name,
        FindCapabilityTool.schema.name,
        RecordConsentTool.schema.name,
        ChangeModeTool.schema.name
      ) ++ BulkOverlayTools.map(_.schema.name),
      tools = ToolPolicy.Standard,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def seedSuggestedTools(convId: Id[Conversation],
                                 names: List[String]): Task[Unit] = {
    val cr = CapabilityResults(
      matches = names.map(n =>
        CapabilityMatch(
          name = n,
          description = s"stub: $n",
          capabilityType = CapabilityType.Tool,
          score = 1.0,
          status = CapabilityStatus.Ready
        )),
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicId,
      query = "seed",
      state = EventState.Complete,
      origin = Some(Event.id())
    )
    TestSigil.publish(cr).map(_ => ())
  }

  private def waitUntil(cond: => Boolean, deadlineMs: Long): Task[Unit] = {
    val end = System.currentTimeMillis() + deadlineMs
    def loop(): Task[Unit] =
      if (cond || System.currentTimeMillis() > end) Task.unit
      else Task.sleep(50.millis).flatMap(_ => loop())
    loop()
  }

  // Tiny iterator → List helper.
  extension [A](it: java.util.Iterator[A]) {
    private def asScalaList: List[A] = {
      val b = List.newBuilder[A]
      while (it.hasNext) b += it.next()
      b.result()
    }
  }

  // ---- tests ----

  "Provider.emergencyShed (sigil #305)" should {

    "preserve projection.suggestedTools on the wire when budget pressure triggers the shed" in {
      installFinder()
      val captured = new ConcurrentLinkedQueue[ProviderCall]()
      TestSigil.setProvider(Task.pure(new CapturingProvider(captured)))
      val convId = Conversation.id(s"wire-suggested-${rapid.Unique()}")
      val agent = makeAgent()
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- seedSuggestedTools(convId, List("grep", "dispatch_workers"))
        proj <- TestSigil.projectionFor(TestAgent, convId)
        _ = proj.suggestedTools.map(_.value) should contain allOf ("grep", "dispatch_workers")
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("act")),
          state = EventState.Complete
        ))
        // Filter to ConversationRequest-driven calls only — classifier
        // consults also call the provider but with empty `tools`. The
        // agent's ConversationRequest carries a non-empty tools array.
        _ <- waitUntil(captured.iterator().asScalaList.exists(_.tools.nonEmpty), deadlineMs = 8_000L)
      } yield {
        val agentCalls = captured.iterator().asScalaList.filter(_.tools.nonEmpty)
        agentCalls should not be empty
        val toolNames = agentCalls.head.tools.map(_.schema.name.value).toSet
        // The wire MUST carry the names projection.suggestedTools
        // advertises — without them, the agent's discovered roster
        // dies between turns. emergencyShed today strips them.
        toolNames should contain("grep")
        toolNames should contain("dispatch_workers")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.clearToolFinder()
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
