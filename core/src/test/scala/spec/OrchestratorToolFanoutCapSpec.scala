package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageDisposition, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}
import spice.http.HttpRequest

/** A trivial action tool taking a distinct arg, so N calls aren't collapsed
  * by the identical-call dedupe. */
case class ProbeInput(n: Int) extends ToolInput derives RW

case object ProbeTool extends Tool {
  type Input  = ProbeInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[ProbeInput]]
  val outputRW = summon[RW[TextToolOutput]]
  override val name = ToolName("probe")
  override val description = "A trivial probe tool."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("test", "probe"))
  )
  override def executeResult(input: ProbeInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(s"probe-${input.n}")))
}

/**
 * Proves #369 — a model that fires a whole discovered tool family in ONE
 * response (10 `bsp_*` when it needed one) must have the excess refused. The
 * #366 breaker only caps IDENTICAL calls; distinct calls all dispatched. This
 * fires `maxToolCallsPerResponse + 3` distinct calls in a single completion
 * and asserts the excess is refused with a corrective note while the cap's
 * worth still executes (legitimate parallelism preserved).
 */
class OrchestratorToolFanoutCapSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "fanout")
  private val cap = TestSigil.maxToolCallsPerResponse
  private val total = cap + 3

  private class FanoutProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val calls = (0 until total).toList.flatMap { i =>
        val cid = CallId(s"call-$i")
        List(
          ProviderEvent.ToolCallStart(cid, "probe"),
          ProviderEvent.toolCall(cid, ProbeTool)(ProbeInput(i))
        )
      }
      Stream.emits(calls :+ ProviderEvent.Done(StopReason.Complete))
    }
  }

  "Orchestrator (tool fan-out cap)" should {
    "refuse action-tool calls past maxToolCallsPerResponse in one response" in {
      val convId = Conversation.id(s"fanout-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = ConversationRequest(
        conversationId     = convId,
        model              = TestSigil.testModel(modelId),
        instructions       = Instructions(),
        turnInput          = TurnInput(conversationId = convId),
        currentMode        = ConversationMode,
        currentTopic       = TestTopicEntry,
        generationSettings = GenerationSettings(),
        tools              = Vector(ProbeTool),
        chain              = List(TestUser, TestAgent)
      )
      Orchestrator.process(TestSigil, new FanoutProvider, request, conv).toList.map { signals =>
        val invokes = signals.collect { case t: ToolInvoke => t }
        val executed = signals.collect {
          case d: sigil.signal.ToolDelta
            if d.state.contains(EventState.Complete)
              && d.output.exists(_.isInstanceOf[TextToolOutput]) => d
        }
        val refusals = signals.collect {
          case m: Message
            if m.role == MessageRole.Tool
              && m.disposition.isInstanceOf[MessageDisposition.Failure]
              && m.content.exists {
                   case sigil.tool.model.ResponseContent.Text(t) => t.contains("more than")
                   case _ => false
                 } => m
        }
        withClue(s"cap=$cap total=$total invokes=${invokes.size} executed=${executed.size} refusals=${refusals.size}: ") {
          invokes.size shouldBe total           // every call gets an invoke
          executed.size shouldBe cap             // exactly the cap's worth run
          refusals.size shouldBe (total - cap)   // the excess is refused with a note
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
