package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, FindCapabilityInput, FindCapabilityTool, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Coverage for the refusal-challenge intercept (sigil bug #126). When
 * an atomic `respond` reads as a refusal AND the agent didn't call
 * `find_capability` since the last user message AND no prior
 * `_refusal_challenge` exists on the conversation tail, the orchestrator
 * suppresses `executeAtomic` and emits a synthetic
 * `_refusal_challenge` ToolInvoke + Tool-role Failure paired to it.
 * The agent re-runs with the Failure visible in context.
 */
class OrchestratorRefusalChallengeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "refusal-challenge")

  /**
   * Emits a single atomic `respond` whose content is configurable.
   * Records nothing — the spec inspects SigilDB.events directly.
   */
  final private class RespondOnceProvider(content: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"call-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          callId,
          RespondInput(topicLabel = "Refusal", topicSummary = "challenge spec", content = content, endsTurn = true)
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /**
   * First call emits `find_capability`; subsequent calls emit a
   * refusal respond. Used to verify that an agent that DID consult
   * the catalog can still refuse without triggering a challenge.
   */
  final private class DiscoveryThenRefuseProvider(refusalContent: String) extends Provider {
    private val callIndex = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = callIndex.incrementAndGet()
      val callId = CallId(s"dual-$n-${rapid.Unique()}")
      if (n == 1) {
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, FindCapabilityTool.schema.name.value),
          ProviderEvent.ToolCallComplete(callId, FindCapabilityInput(keywords = "switch model change provider")),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      } else {
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
          ProviderEvent.ToolCallComplete(
            callId,
            RespondInput(topicLabel = "After Discovery", topicSummary = "informed refusal", content = refusalContent, endsTurn = true)
          ),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  /**
   * Every call emits a refusal respond. Used to verify the
   * once-per-turn challenge limit — the agent refuses, gets
   * challenged, refuses again, and the second refusal passes
   * through (the framework challenged once and stepped aside).
   */
  final private class AlwaysRefuseProvider(content: String) extends Provider {
    private val callIndex = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = callIndex.incrementAndGet()
      val callId = CallId(s"refuse-$n-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          callId,
          RespondInput(topicLabel = "Refusal", topicSummary = "stubborn refusal", content = content, endsTurn = true)
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runWithProvider(provider: Provider, convPrefix: String): Task[(Id[Conversation], List[Event])] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"$convPrefix-${rapid.Unique()}")
    val agent = makeAgent()
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("switch to gpt-5.5")),
        state = EventState.Complete
      ))
      _ <- Task.sleep(3.seconds)
      evs <- TestSigil.withDB(_.events.transaction(_.list))
    } yield (convId, evs.filter(_.conversationId == convId))
  }

  "Orchestrator refusal-challenge (sigil bug #126)" should {

    "challenge a refusal when no find_capability was called since the last user message" in
      runWithProvider(new RespondOnceProvider("I can't switch to GPT-5.5 — the model is fixed."), "refuse-no-discovery").map {
        case (_, evs) =>
          // A synthetic `_refusal_challenge` ToolInvoke must land —
          // this is the framework's response to the first iteration's
          // refusal that didn't consult discovery.
          val challengeInvokes = evs.collect {
            case ti: ToolInvoke if ti.toolName.value == "_refusal_challenge" => ti
          }
          withClue(s"events: ${evs.map(e => s"${e.getClass.getSimpleName}").mkString(", ")}: ") {
            challengeInvokes should not be empty
          }

          // The Failure paired to the synthetic invoke must reach
          // the agent (Tool-role, names `find_capability` in the
          // reason) so its next iteration has actionable feedback.
          val failures = evs.collect {
            case m: Message
                if m.role == MessageRole.Tool && m.isFailure &&
                  m.failureReason.exists(_.contains("find_capability")) => m
          }
          failures should not be empty

          // The synthetic invoke and its paired Failure share an
          // origin link (Tool-role events must carry origin — Bug
          // #69 invariant).
          val syntheticId = challengeInvokes.head._id
          failures.exists(_.origin.contains(syntheticId)) shouldBe true
      }

    "NOT challenge when find_capability was called and the agent then refuses on a later turn" in
      runWithProvider(
        new DiscoveryThenRefuseProvider("I can't switch to GPT-5.5 — no model swap is wired."),
        "refuse-after-discovery").map {
        case (_, evs) =>
          // No challenge fires — the agent did the discovery.
          val challengeInvokes = evs.collect {
            case ti: ToolInvoke if ti.toolName.value == "_refusal_challenge" => ti
          }
          challengeInvokes shouldBe empty

          // The respond produced a user-facing Standard-role Message.
          val agentStandardMessages = evs.collect {
            case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard => m
          }
          agentStandardMessages should not be empty
      }

    "NOT challenge when the respond is not actually a refusal" in
      runWithProvider(new RespondOnceProvider("Here's a summary of what I found in your inbox."), "not-a-refusal").map {
        case (_, evs) =>
          val challengeInvokes = evs.collect {
            case ti: ToolInvoke if ti.toolName.value == "_refusal_challenge" => ti
          }
          challengeInvokes shouldBe empty

          val agentStandardMessages = evs.collect {
            case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard => m
          }
          agentStandardMessages should not be empty
      }

    "NOT recurse infinitely — the second refusal passes through after one challenge" in
      runWithProvider(new AlwaysRefuseProvider("I can't help with that — that's beyond what I do."), "stubborn-refusal").map {
        case (_, evs) =>
          // Exactly one challenge fires (the first refusal) — the
          // second refusal sees a prior `_refusal_challenge` on the
          // tail and passes through.
          val challengeInvokes = evs.collect {
            case ti: ToolInvoke if ti.toolName.value == "_refusal_challenge" => ti
          }
          challengeInvokes should have size 1

          // After the challenge, the agent re-runs and emits another
          // refusal — that one DOES land as a user-facing Message.
          val agentStandardMessages = evs.collect {
            case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard => m
          }
          agentStandardMessages should not be empty
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
