package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TopicEntry}
import sigil.db.Model
import sigil.event.{AgentState, Event, Message}
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant, WorkerParticipantId}
import sigil.provider.{Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

import scala.concurrent.duration.*

/**
 * #327 chat-fidelity — a woken agent in a *directed worker
 * sub-conversation* that has nothing to add simply RESTS, exactly like a
 * user who doesn't reply. No `no_response`, no forced synthesis: the
 * framework lets the turn settle silently. This is what makes the
 * supervised bridge terminate naturally (the supervisor relays its
 * result up to the parent and, with nothing left for the worker, rests
 * here; the worker is never re-woken).
 *
 * Drives a real worker conversation with a provider that returns no tool
 * call and asserts the woken agent settles to Idle WITHOUT synthesizing a
 * reply Message.
 */
class DirectedWorkerSilentSettleSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)
  TestWorkflowSigil.cache.merge(TestSigil.knownTestModels).sync()

  // Provider that emits no tool call (and no content) — the "I have
  // nothing to say" turn.
  final private class SilentProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestWorkflowSigil
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
  }
  TestWorkflowSigil.setProvider(Task.pure(new SilentProvider))

  private val modelId = Model.id("test", "model")
  private val supervisorId: AgentParticipantId = WorkflowTestUser.asInstanceOf[AgentParticipantId]
  private val workerId = WorkerParticipantId("silent-worker")

  private def agent(id: AgentParticipantId) = DefaultAgentParticipant(id = id, modelId = modelId)

  private def eventsIn(convId: Id[Conversation]): Task[List[Event]] =
    TestWorkflowSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.filter(_.conversationId == convId))

  private def pollUntil(convId: Id[Conversation], timeout: FiniteDuration)(p: List[Event] => Boolean): Task[List[Event]] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop(): Task[List[Event]] = eventsIn(convId).flatMap { evs =>
      if (p(evs) || System.currentTimeMillis() > deadline) Task.pure(evs)
      else Task.sleep(150.millis).flatMap(_ => loop())
    }
    loop()
  }

  "a woken agent in a directed worker conversation that has nothing to say" should {
    "settle silently — no forced reply Message, no ping-pong" in {
      val parentId = Conversation.id(s"silent-parent-${rapid.Unique()}")
      val wId = Conversation.id(s"silent-worker-${rapid.Unique()}")
      val w = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(agent(supervisorId), agent(workerId)),
        parentConversationId = Some(parentId),
        _id = wId
      )
      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(w)))
        // Supervisor addresses the worker → worker wakes.
        _ <- TestWorkflowSigil.publish(Message(
          participantId = supervisorId,
          conversationId = wId,
          topicId = WorkflowTestTopic.id,
          content = Vector(ResponseContent.Text("Please proceed.")),
          state = EventState.Complete,
          addressees = Some(Set(workerId))
        ))
        // Wait for the worker's claim to settle to Idle (it woke, had
        // nothing to say, and rested).
        settled <- pollUntil(wId, 30.seconds)(_.exists {
          case s: AgentState if s.participantId == workerId && s.state == EventState.Complete => true
          case _ => false
        })
        // Brief grace so any (incorrect) forced reply / re-wake would land.
        _ <- Task.sleep(500.millis)
        finalEvents <- eventsIn(wId)
      } yield {
        withClue(s"worker should have settled (Idle AgentState); events: ${settled.map(_.getClass.getSimpleName)}: ") {
          settled.exists {
            case s: AgentState if s.participantId == workerId && s.state == EventState.Complete => true
            case _ => false
          } shouldBe true
        }
        // The worker produced NO reply Message — it rested silently.
        val workerMessages = finalEvents.collect {
          case m: Message if m.participantId == workerId => m
        }
        withClue(s"worker must not synthesize a reply; messages: ${workerMessages.map(_.content)}: ") {
          workerMessages shouldBe empty
        }
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
