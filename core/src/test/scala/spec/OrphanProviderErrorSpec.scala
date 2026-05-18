package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import spice.http.HttpRequest

/**
 * Coverage for sigil bug #67 — `ProviderEvent.Error` arriving
 * before any tool call has been dispatched (e.g. during
 * pre-flight `/apply-template` / `/tokenize` / capacity-gate
 * evaluation) no longer emits an origin-less Tool-role Message
 * that bug #64's write-side validation refuses. The orchestrator
 * synthesizes a stub `ToolInvoke` and pairs the error to it.
 */
class OrphanProviderErrorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model-67")

  /**
   * Provider that emits `ProviderEvent.Error` immediately —
   * before any ToolCallStart, mirroring a pre-flight failure.
   */
  private class PreflightErrorProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(
        ProviderEvent.Error("simulated pre-flight failure"),
        ProviderEvent.Done(StopReason.Complete)
      ))
  }

  private def runOrchestrator(): Task[List[Signal]] = {
    val convId = Conversation.id("orphan-error-test")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent),
      tools = Vector.empty
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new PreflightErrorProvider, request, conv).toList
    } yield signals
  }

  "Orchestrator (bug #67)" should {

    "synthesize a stub ToolInvoke + pair the error Message to it when ProviderEvent.Error fires before any tool call" in
      runOrchestrator().map { signals =>
        // The synthetic ToolInvoke that gives the error a parent.
        val synthetic = signals.collect {
          case ti: ToolInvoke if ti.toolName.value == "_provider_error" => ti
        }
        synthetic should have size 1
        synthetic.head.internal shouldBe true

        // The Tool-role error Message — paired to the synthetic
        // invoke (NOT origin = None, which bug #64 would refuse).
        val errorMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool && m.content.exists {
                case sigil.tool.model.ResponseContent.Text(t) => t.contains("Provider error")
                case _ => false
              } => m
        }
        errorMessages should have size 1
        errorMessages.head.origin shouldBe Some(synthetic.head._id)

        // Critically: NO orphan Tool-role Message with origin = None.
        // (If one slipped through, bug #64's write-side validation
        // would fire downstream, killing the iteration.)
        val orphanedToolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool && m.origin.isEmpty => m
        }
        orphanedToolMessages shouldBe empty
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
