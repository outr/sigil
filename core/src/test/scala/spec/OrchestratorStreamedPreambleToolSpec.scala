package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{ToolOutcome, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}
import spice.http.HttpRequest

/**
 * Invariant guard (validated during the sigil #354 investigation): a NON-respond
 * tool call that follows streamed preamble text — a `ContentBlockDelta`, the shape
 * Opus emits before a tool_use — must still EXECUTE. `ToolCallStart` clears the
 * `activeMessageCreated` flag the preamble set, so the call resolves through the
 * atomic dispatch path (the streaming-settle `case _` is unreachable for it). This
 * pins that behaviour so a future change to the flag-reset can't silently regress
 * to dropping the tool body.
 *
 * (Note: #354's actual "result did not reach this turn" race is NOT in this path —
 * it's a slow tool whose result-settle is truncated after `ToolCallComplete` has
 * already flipped the invoke to Complete, leaving it Complete-but-Pending.)
 */
class OrchestratorStreamedPreambleToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model-354")

  /** Provider that streams preamble TEXT (creating an active Message) and THEN
    * calls a non-respond tool — the exact shape that dropped the tool body. */
  private class PreambleThenToolProvider extends Provider {
    override def `type`: ProviderType = ProviderType.Anthropic
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val textCid = CallId("preamble")
      val toolCid = CallId("probe-call")
      Stream.emits(List(
        ProviderEvent.ContentBlockStart(textCid, "text", None),
        ProviderEvent.ContentBlockDelta(textCid, "Let me run the probe to check."),
        ProviderEvent.ToolCallStart(toolCid, StreamProbeTool.name.value),
        ProviderEvent.toolCall(toolCid, StreamProbeTool)(StreamProbeInput()),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def run(): Task[List[Signal]] = {
    val convId = Conversation.id(s"streamed-preamble-${rapid.Unique()}")
    val conv   = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      model              = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector(StreamProbeTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new PreambleThenToolProvider, request, conv).toList
    } yield signals
  }

  "A non-respond tool call after streamed preamble text" should {
    "actually EXECUTE the tool, not settle it empty (#354)" in {
      run().map { signals =>
        val probeOutputs = signals.collect {
          case d: ToolDelta => d.output.collect { case t: TextToolOutput => t.text }
        }.flatten
        val settledOutcomes = signals.collect {
          case d: ToolDelta if d.state.contains(sigil.signal.EventState.Complete) => d.outcome
        }.flatten
        withClue(s"probe outputs: $probeOutputs; outcomes: $settledOutcomes\n") {
          // The tool ran: its distinctive output reached the stream.
          probeOutputs.exists(_.contains(StreamProbeTool.Marker)) shouldBe true
          // And the invoke settled Success (not the empty/Pending drop).
          settledOutcomes should contain(ToolOutcome.Success)
        }
        // The preamble produced a real ToolInvoke for the probe.
        signals.collect { case ti: ToolInvoke if ti.toolName == StreamProbeTool.name => ti } should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

final case class StreamProbeInput() extends ToolInput derives RW
case object StreamProbeTool extends Tool {
  val Marker = "PROBE_EXECUTED_354"
  type Input  = StreamProbeInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[StreamProbeInput]]
  val outputRW = summon[RW[TextToolOutput]]
  override val name = ToolName("stream_probe")
  override val description = "Test probe — returns a distinctive marker proving it executed."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("test", "probe", "stream"))
  )
  override def executeResult(input: StreamProbeInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(Marker)))
}
