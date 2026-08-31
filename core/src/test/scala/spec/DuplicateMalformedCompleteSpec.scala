package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageDisposition, MessageRole}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.core.NoResponseTool
import sigil.tool.{DecodeError, DecodeViolation, ViolationKind, WireCall}
import spice.http.HttpRequest

/**
 * A provider that duplicates a tool call's completion — the split-finish
 * quirk the orchestrator's `completedCallIds` guard exists for — must not
 * pair ONE invoke with TWO refusals when the call is `Malformed`. The
 * malformed branch records the call id like every other completion, so
 * the second delivery is dropped by the same guard.
 */
class DuplicateMalformedCompleteSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "split-finish")

  private val malformed = WireCall.Malformed(
    name = NoResponseTool.schema.name.value,
    error = DecodeError(
      List(DecodeViolation(List("reason"), "expected a string", ViolationKind.Structural)),
      fabric.obj("reason" -> fabric.num(42))
    ),
    rawArgs = fabric.obj("reason" -> fabric.num(42))
  )

  /**
   * Emits the SAME malformed completion twice under one call id.
   */
  private class DuplicateMalformedProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
      ProviderEvent.ToolCallStart(CallId("dup-malformed"), NoResponseTool.schema.name.value),
      ProviderEvent.ToolCallComplete(CallId("dup-malformed"), malformed),
      ProviderEvent.ToolCallComplete(CallId("dup-malformed"), malformed),
      ProviderEvent.Done(StopReason.Complete)
    ))
  }

  private def run(): Task[List[Signal]] = {
    val convId = Conversation.id(s"dup-malformed-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent),
      tools = Vector(NoResponseTool)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new DuplicateMalformedProvider, request, conv).toList
    } yield signals
  }

  "a duplicated malformed ToolCallComplete" should {
    "produce exactly one refusal for the one invoke" in
      run().map { signals =>
        val refusals = signals.collect {
          case m: Message if m.role == MessageRole.Tool && m.disposition.isInstanceOf[MessageDisposition.Failure] => m
        }
        withClue(s"signals=${signals.map(_.getClass.getSimpleName)}: ") {
          refusals should have size 1
        }
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
