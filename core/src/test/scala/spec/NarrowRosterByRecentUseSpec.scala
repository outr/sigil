package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolPolicy
}
import sigil.signal.EventState
import sigil.tool.{InMemoryToolFinder, TextToolOutput, Tool, ToolContext, ToolInput, ToolName}
import sigil.tool.core.{CoreTools, FindCapabilityTool, RespondOptionsTool, RespondTool}
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Sigil #304 — when `narrowRosterByRecentUse = true` and a recent
 * tool invocation has settled into the projection's rolling window,
 * the next wire `ProviderCall.tools` must reflect the narrowing
 * decision `Sigil.effectiveToolNames` makes.
 *
 * The field-observed failure (Shopkeeper wire log: 95 main-agent
 * requests, all 45 tools, byte-identical) is the cross-turn case:
 * the prior framework wiped `recentToolInvocations` at every
 * `runAgent` entry, so iteration 1 of every turn saw empty recent
 * and the first-iteration safety fired — which for apps whose
 * turns are mostly single-iteration (atomic respond after a tool
 * call) meant narrowing NEVER engaged on the wire. This spec drives
 * a follow-up turn after a tool-invoking prior turn and asserts the
 * follow-up's wire roster reflects the prior turn's usage.
 */
class NarrowRosterByRecentUseSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "narrow-wire")
  private val testModel: Model = {
    val now = lightdb.time.Timestamp()
    Model(
      canonicalSlug = modelId.value,
      huggingFaceId = "",
      name = modelId.value,
      description = "Synthetic model for sigil #304 wire-narrowing coverage.",
      contextLength = 200_000L,
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
        contextLength = Some(200_000L),
        maxCompletionTokens = Some(2_048L),
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
  TestSigil.cache.merge(List(testModel)).sync()

  case class NarrowingStubInput(arg: String = "") extends ToolInput derives RW

  ToolInput.register(summon[RW[NarrowingStubInput]])

  private def stubTool(n: String): Tool = new Tool {
    type Input = NarrowingStubInput
    type Output = TextToolOutput
    val inputRW = summon[RW[NarrowingStubInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName(n)
    val description = s"Synthetic tool $n."
    override def executeOutput(input: NarrowingStubInput, ctx: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput("ok"))
  }

  private val BaselineSize = 10
  private val Baseline: List[Tool] = (1 to BaselineSize).toList.map(i => stubTool(s"app_tool_$i"))
  private val UsedTool: Tool = Baseline(2) // app_tool_3

  /**
   * Scripts the Shopkeeper pattern — a single-iteration turn that
   * emits both a non-terminal tool call AND a terminal respond in
   * the same provider response. Every call is shaped this way so
   * subsequent turns also single-iterate.
   */
  final private class SingleIterTwoCallsProvider(
    captured: ConcurrentLinkedQueue[ProviderCall]
  ) extends Provider {
    private val callCount = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      captured.add(input)
      val n = callCount.incrementAndGet()
      val toolCid = CallId(s"call-$n-tool")
      val respondCid = CallId(s"call-$n-respond")
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ToolCallStart(toolCid, UsedTool.schema.name.value),
        ProviderEvent.ToolCallComplete(toolCid, NarrowingStubInput(s"args-$n")),
        ProviderEvent.ToolCallStart(respondCid, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          respondCid,
          _root_.sigil.tool.model.RespondInput(
            topicLabel = "T",
            topicSummary = "test",
            content = s"reply $n",
            endsTurn = true
          )
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def installFinder(): Unit = {
    val tools = CoreTools.all.toList ++ Baseline
    TestSigil.setToolFinder(InMemoryToolFinder(tools.distinctBy(_.name)))
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = List(
        RespondTool.schema.name,
        RespondOptionsTool.schema.name,
        FindCapabilityTool.schema.name
      ) ++ Baseline.map(_.schema.name),
      tools = ToolPolicy.Standard,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def waitUntil(cond: => Boolean, deadlineMs: Long): Task[Unit] = {
    val end = System.currentTimeMillis() + deadlineMs
    def loop(): Task[Unit] =
      if (cond || System.currentTimeMillis() > end) Task.unit
      else Task.sleep(50.millis).flatMap(_ => loop())
    loop()
  }

  extension [A](it: java.util.Iterator[A]) {
    private def asScalaList: List[A] = {
      val b = List.newBuilder[A]
      while (it.hasNext) b += it.next()
      b.result()
    }
  }

  private def callsFor(captured: ConcurrentLinkedQueue[ProviderCall],
                       convId: Id[Conversation]): List[ProviderCall] =
    captured.iterator().asScalaList
      .filter(c => c.conversationId.contains(convId) && c.tools.nonEmpty)

  /**
   * Block until the agent's [[sigil.event.AgentState]] lock for
   * `(agent, conv)` has settled to `Complete` (released claim). The
   * cross-turn assertion only holds when turn 1's loop fully
   * terminates BEFORE turn 2's user message lands — otherwise the
   * second message becomes a within-loop external trigger and
   * `runAgent` doesn't re-fire.
   */
  private def waitForAgentIdle(convId: Id[Conversation],
                               agentId: sigil.participant.ParticipantId,
                               deadlineMs: Long): Task[Unit] = {
    val lockId: lightdb.id.Id[sigil.event.Event] =
      lightdb.id.Id(s"agentlock:${agentId.value}:${convId.value}")
    val end = System.currentTimeMillis() + deadlineMs
    def loop(): Task[Unit] =
      TestSigil.withDB(_.eventsTransaction(convId)(_.get(lockId))).flatMap {
        case Some(s: sigil.event.AgentState) if s.state == EventState.Complete => Task.unit
        case _ if System.currentTimeMillis() > end => Task.unit
        case _ => Task.sleep(50.millis).flatMap(_ => loop())
      }
    loop()
  }

  "Sigil #304 — narrowRosterByRecentUse end-to-end" should {

    "narrow a follow-up turn's wire roster using the prior turn's recent invocations" in {
      TestSigil.narrowRosterByRecentUseOverride.set(Some(true))
      installFinder()
      val captured = new ConcurrentLinkedQueue[ProviderCall]()
      TestSigil.setProvider(Task.pure(new SingleIterTwoCallsProvider(captured)))
      val convId = Conversation.id(s"narrow-cross-turn-${rapid.Unique()}")
      val agent = makeAgent()
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        // Turn 1: a single iteration that emits both `app_tool_3` and
        // `respond` — matching Shopkeeper's pattern. The tool call
        // lands in the projection, then `respond(endsTurn=true)`
        // terminates the loop and releases the agent lock.
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("first request")),
          state = EventState.Complete
        ))
        _ <- waitUntil(callsFor(captured, convId).nonEmpty, deadlineMs = 10_000L)
        // Turn 1 must FULLY terminate (lock release) before turn 2's
        // user Message lands — otherwise turn 2 piggybacks onto the
        // still-active loop instead of firing a new `runAgent` claim,
        // and the cross-turn re-entry path under test isn't exercised.
        _ <- waitForAgentIdle(convId, TestAgent, deadlineMs = 10_000L)
        turn1Count = callsFor(captured, convId).size
        // Confirm the projection actually recorded the invoke before
        // turn 2 fires — otherwise the cross-turn test could fail for
        // an unrelated reason.
        proj <- TestSigil.projectionFor(TestAgent, convId)
        _ = proj.recentToolInvocations.map(_.toolName.value) should contain("app_tool_3")
        // Turn 2: a fresh user Message triggers a new `runAgent` claim.
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("second request")),
          state = EventState.Complete
        ))
        _ <- waitUntil(callsFor(captured, convId).size > turn1Count, deadlineMs = 10_000L)
      } yield {
        val allCalls = callsFor(captured, convId)
        val turn2Calls = allCalls.drop(turn1Count)
        turn2Calls should not be empty
        val turn2FirstNames = turn2Calls.head.tools.map(_.schema.name.value).toSet
        // The projection's rolling window must survive the turn
        // boundary — turn 2's iteration 1 sees `app_tool_3` in recent
        // and narrows the wire roster to it (plus essentials). The
        // pre-#304 behaviour wiped recent at every `runAgent` start,
        // so this assertion fails on the snapshot: turn2FirstNames
        // would contain the full 10 baseline names.
        turn2FirstNames should contain("app_tool_3")
        turn2FirstNames should contain(FindCapabilityTool.schema.name.value)
        turn2FirstNames should contain(RespondTool.schema.name.value)
        val droppedNames = (1 to BaselineSize).map(i => s"app_tool_$i").toSet - "app_tool_3"
        turn2FirstNames.intersect(droppedNames) shouldBe Set.empty[String]
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.narrowRosterByRecentUseOverride.set(None)
      TestSigil.clearToolFinder()
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
