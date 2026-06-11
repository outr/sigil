package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger

/**
 * Sigil #392 — when the forced `tool_choice` self-heal (#387) downgrades to
 * `auto` for Fable/Mythos 5, the model often answers with plain text +
 * `end_turn` and no tool call. That complete prose answer must be COMMITTED
 * on the first occurrence — not dropped and re-requested (which produced ~4
 * duplicate, never-committing bubbles before forced synthesis finally landed
 * a `respond`).
 */
class NakedTextTerminalSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "naked-text")
  TestSigil.testModel(modelId)

  private val answer = "Done — the working draft theme is now named \"Use Huron Test\"."

  /** Always answers with naked text + end_turn (Complete), no tool call —
    * exactly the auto-downgraded Fable/Mythos terminal turn. Counts calls so
    * the test can prove it isn't re-requested. */
  private final class NakedTextProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.incrementAndGet()
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ContentBlockDelta(CallId("naked-text-0"), answer),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings()
    )

  private def runUserTurn(provider: Provider): Task[Id[Conversation]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"naked-text-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Rename the theme.")),
             state          = EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId)
    } yield convId
  }

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "Naked-text terminal answer (sigil #392)" should {

    "commit the prose on the FIRST turn — no re-request, no duplicate bubbles" in {
      val provider = new NakedTextProvider
      for {
        convId <- runUserTurn(provider)
        evs    <- eventsFor(convId)
      } yield {
        // The model was called exactly once — the prose wasn't dropped and
        // re-requested (pre-fix it took ~4 retries + a forced synthesis).
        provider.calls.get() shouldBe 1
        // The agent's terminal answer committed as a single Complete,
        // user-visible Standard Message carrying the prose.
        val replies = evs.collect {
          case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard
                          && m.state == EventState.Complete && m.isSuccess => m
        }
        replies should have size 1
        replies.head.content.collect { case t: ResponseContent.Text => t.text }.mkString should include("Use Huron Test")
        // No Failure bubble.
        evs.collect { case m: Message if m.isFailure => m } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
