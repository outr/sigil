package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import sigil.tool.core.RespondTool

/**
 * Regression for the terminal-signal commit-ordering guarantee:
 * when a turn settles, `runAgentLoop` must broadcast the terminal
 * `AgentStateDelta(Idle, Complete)` only AFTER the iteration's
 * batched event transaction has committed.
 *
 * A consumer that reacts to "turn complete" by reading the
 * conversation back must find the turn's `respond` Message already
 * durable. Earlier the terminal delta was published from inside the
 * batched transaction, so a fresh read on "turn complete" raced the
 * commit and could miss the reply.
 *
 * Drives one stub-provider turn through the full `runAgentLoop`,
 * taps the signal stream for the terminal delta, and — at the
 * instant it arrives — reads the event log in a fresh transaction
 * (committed data only) and checks the batched-event scope is closed.
 */
class TerminalSignalCommitOrderingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProvider(Task.pure(Bug255StubProvider))

  "runAgentLoop terminal signal" should {
    "publish AgentStateDelta(Idle, Complete) only after the iteration's events commit" in {
      val convId = Conversation.id(s"terminal-commit-${rapid.Unique()}")
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = Bug255StubProvider.modelId,
        toolNames = Nil,
        instructions = Instructions(),
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
      )
      // (respondCommitted, batchedScopeStillOpen) captured at the
      // instant the terminal delta is received.
      val observed = new AtomicReference[Option[(Boolean, Boolean)]](None)
      @volatile var running = true
      TestSigil.signals
        .takeWhile(_ => running)
        .evalMap {
          case d: AgentStateDelta
              if d.conversationId == convId
                && d.activity.contains(AgentActivity.Idle)
                && d.state.contains(EventState.Complete) =>
            for {
              committed <- TestSigil.withDB(_.events.transaction(_.stream.toList))
              scopeOpen <- TestSigil.withDB(db => Task(db.batchedEventScope(convId).isDefined))
            } yield {
              val respondCommitted = committed.exists {
                case m: Message => m.participantId == TestAgent
                case _ => false
              }
              observed.compareAndSet(None, Some((respondCommitted, scopeOpen)))
              ()
            }
          case _ => Task.unit
        }
        .drain
        .startUnit()

      for {
        _ <- TestSigil.newConversation(createdBy = TestUser, participants = List(agent), conversationId = convId)
        conv <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
          .map(_.getOrElse(fail("conversation not created")))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = conv.currentTopicId,
          content = Vector(ResponseContent.Text("hello")),
          state = EventState.Complete
        ))
        result <- pollUntilObserved(observed)
      } yield {
        running = false
        result match {
          case Some((respondCommitted, scopeOpen)) =>
            withClue("the turn's respond Message must be committed when the terminal delta fires: ") {
              respondCommitted shouldBe true
            }
            withClue("the batched-event scope must be closed when the terminal delta fires: ") {
              scopeOpen shouldBe false
            }
          case None => fail("terminal AgentStateDelta(Idle, Complete) was never observed")
        }
      }
    }
  }

  private def pollUntilObserved(observed: AtomicReference[Option[(Boolean, Boolean)]],
                                timeout: FiniteDuration = 15.seconds): Task[Option[(Boolean, Boolean)]] = {
    def loop(remainingMs: Long): Task[Option[(Boolean, Boolean)]] =
      observed.get() match {
        case some @ Some(_) => Task.pure(some)
        case None if remainingMs <= 0 => Task.pure(None)
        case None => Task.sleep(50.millis).flatMap(_ => loop(remainingMs - 50))
      }
    loop(timeout.toMillis)
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/**
 * Stub provider — emits a single atomic `respond` so the agent loop
 * runs exactly one iteration and terminates cleanly.
 */
private object Bug255StubProvider extends Provider {
  val modelId: Id[Model] = Model.id("bug255-stub")
  TestSigil.testModel(modelId)

  override def `type`: ProviderType = ProviderType.LlamaCpp
  override def models: List[Model] = Nil
  override protected def sigil: Sigil = TestSigil

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    Task.error(new UnsupportedOperationException("Bug255StubProvider"))

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    // Skip classifier subcalls (no `respond` in the tool roster).
    val isPrimary = input.tools.exists(_.name.value == "respond")
    if (!isPrimary) Stream.empty
    else {
      val callId = CallId("bug255-respond")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "respond"),
        ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
          topicLabel = "Reply",
          topicSummary = "Reply",
          content = "bug255-reply",
          endsTurn = true
        )),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }
}
