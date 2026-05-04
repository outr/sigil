package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.role.Role
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.AgentState
import sigil.participant.{AgentParticipant, DefaultAgentParticipant, ParticipantId}
import sigil.provider.{
  CallId,
  GenerationSettings,
  Instructions,
  Provider,
  ProviderCall,
  ProviderEvent,
  ProviderType,
  StopReason
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.ToolName
import sigil.{Sigil, TurnContext}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Coverage for the [[Sigil.fireGreeting]] lifecycle:
 *   - fresh-conversation greet via [[Sigil.newConversation]]
 *   - late-join greet via [[Sigil.addParticipant]]
 *   - agent-level [[AgentParticipant.greetsOnJoin]] flag gates firing
 *   - no-greet path (agent with `greetsOnJoin = false` is never fired
 *     by the lifecycle hooks)
 *
 * Uses a no-op stub provider so the assertions can rely on the
 * dispatcher's lifecycle signals (AgentState lock claim, processGreeting
 * invocation count) without needing a live LLM.
 */
class GreetOnJoinSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  TestSigil.setProvider(Task.pure(NoOpStubProvider))

  private def freshConvId(suffix: String): Id[Conversation] =
    Conversation.id(s"greet-$suffix-${rapid.Unique()}")

  private val plannerRole = Role(
    name = "planner",
    description = "On entering an empty conversation, introduce yourself."
  )

  private val workerRole = Role(
    name = "worker",
    description = "Reacts to triggers; never greets."
  )

  private def agent(toolNames: List[ToolName] = Nil,
                    roles: List[Role] = List(plannerRole),
                    greetsOnJoin: Boolean = false): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = NoOpStubProvider.modelId,
      toolNames = toolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      greetsOnJoin = greetsOnJoin,
      roles = roles
    )

  private def claimsFor(broadcaster: RecordingBroadcaster, agentId: String): List[AgentState] =
    broadcaster.recorded.collect { case s: AgentState => s }
      .filter(_.agentId.value == agentId)
      .filter(_.state == EventState.Active)

  private def awaitClaim(broadcaster: RecordingBroadcaster,
                         agentId: String,
                         timeoutMs: Long = 5000): Task[Boolean] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop: Task[Boolean] =
      if (claimsFor(broadcaster, agentId).nonEmpty) Task.pure(true)
      else if (System.currentTimeMillis() > deadline) Task.pure(false)
      else Task.sleep(25.millis).flatMap(_ => loop)
    loop
  }

  private def awaitSettled(broadcaster: RecordingBroadcaster,
                           timeoutMs: Long = 5000): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop: Task[Unit] = {
      val settled = broadcaster.recorded.exists {
        case d: AgentStateDelta =>
          d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete)
        case _ => false
      }
      if (settled) Task.unit
      else if (System.currentTimeMillis() > deadline)
        Task.error(new RuntimeException(s"awaitSettled timed out after ${timeoutMs}ms"))
      else Task.sleep(25.millis).flatMap(_ => loop)
    }
    loop
  }

  private val negativeWindow = 250.millis

  "Sigil.fireGreeting" should {
    "fire when AgentParticipant.greetsOnJoin = true (newConversation)" in {
      val recorder = new RecordingBroadcaster
      recorder.attach(TestSigil)
      NoOpStubProvider.callCount.set(0)
      val convId = freshConvId("fresh-greet")
      val a      = agent(greetsOnJoin = true)
      for {
        _      <- TestSigil.newConversation(createdBy = TestUser, participants = List(a), conversationId = convId)
        seen   <- awaitClaim(recorder, TestAgent.value)
        _      <- awaitSettled(recorder)
        claims  = claimsFor(recorder, TestAgent.value)
      } yield {
        seen shouldBe true
        claims should not be empty
        claims.head.conversationId shouldBe convId
        NoOpStubProvider.callCount.get() should be >= 1
      }
    }

    "stay silent when AgentParticipant.greetsOnJoin = false" in {
      val recorder = new RecordingBroadcaster
      recorder.attach(TestSigil)
      NoOpStubProvider.callCount.set(0)
      val convId = freshConvId("fresh-no-greet")
      val a      = agent(greetsOnJoin = false)
      for {
        _ <- TestSigil.newConversation(createdBy = TestUser, participants = List(a), conversationId = convId)
        _ <- Task.sleep(negativeWindow)
      } yield {
        claimsFor(recorder, TestAgent.value) shouldBe empty
        NoOpStubProvider.callCount.get() shouldBe 0
      }
    }

    "fire exactly once for an agent with multiple roles when greetsOnJoin = true" in {
      val recorder = new RecordingBroadcaster
      recorder.attach(TestSigil)
      NoOpStubProvider.callCount.set(0)
      val convId = freshConvId("multi-role")
      val a      = agent(roles = List(plannerRole, workerRole), greetsOnJoin = true)
      for {
        _ <- TestSigil.newConversation(createdBy = TestUser, participants = List(a), conversationId = convId)
        _ <- awaitClaim(recorder, TestAgent.value)
        _ <- awaitSettled(recorder)
      } yield {
        // Merged dispatch: regardless of role count, the agent's greeting
        // produces exactly one provider call (one merged turn).
        NoOpStubProvider.callCount.get() shouldBe 1
      }
    }

    "fire on late-join via Sigil.addParticipant" in {
      val recorder = new RecordingBroadcaster
      recorder.attach(TestSigil)
      NoOpStubProvider.callCount.set(0)
      val convId = freshConvId("late-join")
      val a      = agent(greetsOnJoin = true)
      for {
        _      <- TestSigil.newConversation(createdBy = TestUser, conversationId = convId)
        _      <- Task.sleep(negativeWindow)
        before  = claimsFor(recorder, TestAgent.value)
        _      <- TestSigil.addParticipant(convId, a)
        seen   <- awaitClaim(recorder, TestAgent.value)
        _      <- awaitSettled(recorder)
      } yield {
        before shouldBe empty
        seen shouldBe true
        NoOpStubProvider.callCount.get() should be >= 1
      }
    }

    "be a no-op when addParticipant adds an agent with greetsOnJoin = false" in {
      val recorder = new RecordingBroadcaster
      recorder.attach(TestSigil)
      NoOpStubProvider.callCount.set(0)
      val convId = freshConvId("late-join-silent")
      val a      = agent(greetsOnJoin = false)
      for {
        _ <- TestSigil.newConversation(createdBy = TestUser, conversationId = convId)
        _ <- TestSigil.addParticipant(convId, a)
        _ <- Task.sleep(negativeWindow)
      } yield {
        claimsFor(recorder, TestAgent.value) shouldBe empty
        NoOpStubProvider.callCount.get() shouldBe 0
      }
    }

    "addParticipant is idempotent when the agent is already in the conversation" in {
      val recorder = new RecordingBroadcaster
      recorder.attach(TestSigil)
      NoOpStubProvider.callCount.set(0)
      val convId = freshConvId("idempotent")
      val a      = agent(greetsOnJoin = true)
      for {
        _          <- TestSigil.newConversation(createdBy = TestUser, participants = List(a), conversationId = convId)
        _          <- awaitClaim(recorder, TestAgent.value)
        _          <- awaitSettled(recorder)
        firstCount  = NoOpStubProvider.callCount.get()
        _          <- TestSigil.addParticipant(convId, a)
        _          <- Task.sleep(negativeWindow)
      } yield {
        NoOpStubProvider.callCount.get() shouldBe firstCount
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/** Stub provider used by [[GreetOnJoinSpec]]. Emits exactly one
  * `Done(StopReason.Complete)` per call so the agent loop terminates
  * cleanly without an LLM round-trip. */
private object NoOpStubProvider extends Provider {
  val modelId: Id[Model] = Model.id("noop-stub")

  val callCount: AtomicInteger = new AtomicInteger(0)
  val lastChain = new java.util.concurrent.atomic.AtomicReference[List[ParticipantId]](Nil)

  override def `type`: ProviderType = ProviderType.LlamaCpp
  override def models: List[Model] = Nil
  override protected def sigil: Sigil = TestSigil

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    Task.error(new UnsupportedOperationException("NoOpStubProvider"))

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    callCount.incrementAndGet()
    Stream.emit(ProviderEvent.Done(StopReason.Complete))
  }
}
