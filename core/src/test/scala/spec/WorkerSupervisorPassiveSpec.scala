package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TopicEntry}
import sigil.db.Model
import sigil.event.{AgentState, Event, Message, MessageRole}
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, ParticipantId}
import sigil.provider.{GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.Sigil
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Sigil #352 — in a directed worker conversation the supervisor must stay
 * passive: it is woken ONLY when the worker addresses it (a relay/question),
 * never by the worker's own tool results or other intermediate events.
 *
 * Before the fix, `TriggerFilter`'s `role == Tool => true` rule (which runs
 * ahead of the addressee check) cross-woke every participant on each worker
 * tool call, so the supervisor spun up its own generic coding loop and ground
 * the same task concurrently with the worker. `Sigil.shouldWake` now restricts
 * cross-participant fan-out in a directed worker conversation to addressed
 * `Standard` messages.
 */
class WorkerSupervisorPassiveSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "worker-passive")
  TestSigil.testModel(modelId)

  /** Any agent that is woken settles immediately — no work loop. */
  private object SilentProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
  }
  TestSigil.setProvider(Task.pure(SilentProvider))

  // Both ids must be registered in TestSigil so the persisted directed
  // conversation round-trips with two agents. The supervisor/worker
  // distinction in the fix is by ADDRESSING, not participant type, so any
  // two registered agents exercise the fan-out wake path faithfully.
  private val supervisorId: AgentParticipantId = TestAgent
  private val workerId: AgentParticipantId = StrictAgent

  private def agentP(id: AgentParticipantId): AgentParticipant =
    DefaultAgentParticipant(
      id = id, modelId = modelId, instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def supervisorClaims(rec: RecordingBroadcaster, convId: Id[Conversation]): List[AgentState] =
    rec.recorded.collect { case s: AgentState => s }
      .filter(_.agentId == supervisorId)
      .filter(_.state == EventState.Active)
      .filter(_.conversationId == convId)

  private def awaitSupervisorClaim(rec: RecordingBroadcaster, convId: Id[Conversation], timeoutMs: Long = 4000): Task[Boolean] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop: Task[Boolean] =
      if (supervisorClaims(rec, convId).nonEmpty) Task.pure(true)
      else if (System.currentTimeMillis() > deadline) Task.pure(false)
      else Task.sleep(25.millis).flatMap(_ => loop)
    loop
  }

  private val negativeWindow = 500.millis

  /** A directed worker conversation [supervisor, worker], linked to a parent. */
  private def workerConv(): Task[Conversation] = {
    val convId   = Conversation.id(s"worker-passive-${rapid.Unique()}")
    val parentId = Conversation.id(s"worker-passive-parent-${rapid.Unique()}")
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      topics               = List(TopicEntry(TestTopicId, "t", "t")),
      participants         = List(agentP(supervisorId), agentP(workerId)),
      parentConversationId = Some(parentId),
      _id                  = convId
    ))))
  }

  "a directed worker conversation (#352)" should {

    "NOT wake the supervisor on the worker's tool-role result" in {
      val rec = new RecordingBroadcaster; rec.attach(TestSigil)
      workerConv().flatMap { conv =>
        for {
          // A worker tool result: a Tool-role event (carries `origin`, as
          // every paired tool result does). Under the old TriggerFilter
          // this woke EVERY participant (line 48) — the supervisor too.
          _ <- TestSigil.publish(Message(
                 participantId  = workerId,
                 conversationId = conv._id,
                 topicId        = conv.currentTopicId,
                 role           = MessageRole.Tool,
                 content        = Vector(ResponseContent.Text("grep: 31 matches in 8 files")),
                 origin         = Some(Event.id()),
                 state          = EventState.Complete
               ))
          _ <- Task.sleep(negativeWindow)
        } yield supervisorClaims(rec, conv._id) shouldBe empty
      }
    }

    "wake the supervisor only when the worker addresses it (relay / question)" in {
      val rec = new RecordingBroadcaster; rec.attach(TestSigil)
      workerConv().flatMap { conv =>
        for {
          _    <- TestSigil.publish(Message(
                    participantId  = workerId,
                    conversationId = conv._id,
                    topicId        = conv.currentTopicId,
                    role           = MessageRole.Standard,
                    content        = Vector(ResponseContent.Text("Found the references; should I write the report?")),
                    addressees     = Some(Set[ParticipantId](supervisorId)),
                    state          = EventState.Complete
                  ))
          woke <- awaitSupervisorClaim(rec, conv._id)
        } yield woke shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
