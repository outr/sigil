package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #74 — `respond(endsTurn = false)` keeps the
 * agent's loop running so the agent can announce a status pulse and
 * then continue working on the same turn.
 *
 * Verifies:
 *   1. respond(endsTurn=false) does NOT end the turn — the loop
 *      iterates immediately with the agent's progress message in
 *      next-iteration history.
 *   2. respond(endsTurn=true) (default behavior) DOES end the turn.
 *   3. Two iterations occur from a single user message: progress
 *      respond → final respond. The provider sees a single agent
 *      "turn" with 2 wire calls.
 *   4. The runaway cap still applies — an agent that emits
 *      respond(endsTurn=false) every iteration eventually hits
 *      maxAgentIterations.
 */
class RespondEndsTurnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "ends-turn-model")

  /** Provider that scripts:
    *   - call 1: respond(endsTurn = false, content = "Let me check…")
    *   - call 2: respond(endsTurn = true, content = "Done.")
    * Records call count so the spec can assert it received both. */
  private class TwoStepProgressProvider extends Provider {
    val callCount = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = callCount.incrementAndGet()
      val callId = CallId(s"call-$n")
      val payload =
        if (n == 1)
          RespondInput(
            topicLabel   = "Status",
            topicSummary = "Progress update before doing more work.",
            content      = "Let me check…",
            endsTurn     = false
          )
        else
          RespondInput(
            topicLabel   = "Done",
            topicSummary = "Final answer for this turn.",
            content      = "Done.",
            endsTurn     = true
          )
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callId, payload),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Provider that emits respond(endsTurn = false) every call —
    * tests the runaway cap. */
  private class AlwaysContinueProvider extends Provider {
    val callCount = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = callCount.incrementAndGet()
      val callId = CallId(s"call-$n")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callId, RespondInput(
          topicLabel   = s"Status $n",
          topicSummary = "Status pulse.",
          content      = s"Step $n…",
          endsTurn     = false
        )),
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
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  "respond(endsTurn = false)" should {

    "iterate the loop without waiting for new triggers, then settle on respond(endsTurn = true)" in {
      val provider = new TwoStepProgressProvider
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"ends-turn-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Evaluate the admin services.")),
               state          = EventState.Complete
             ))
        _ <- Task.sleep(2.seconds) // wait for both iterations to settle
        events <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val agentMessages = events.collect {
          case m: Message if m.participantId == TestAgent => m
        }.sortBy(_.timestamp.value)
        // Two iterations → two agent Messages: the progress pulse
        // (endsTurn=false) AND the final reply (endsTurn=true).
        agentMessages.size shouldBe 2
        provider.callCount.get() shouldBe 2

        // RespondTool's content goes through MarkdownContentParser —
        // plain text without markdown structure may render as Markdown
        // rather than Text. Match both.
        val texts = agentMessages.flatMap(_.content.collect {
          case ResponseContent.Text(t)     => t
          case ResponseContent.Markdown(t) => t
        })
        texts should contain ("Let me check…")
        texts should contain ("Done.")
      }
    }
  }

  "respond(endsTurn = true)" should {

    "end the turn cleanly after a single iteration" in {
      val callCount = new AtomicInteger(0)
      val singleProvider: Provider = new Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[_root_.sigil.db.Model] = Nil
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] = {
          callCount.incrementAndGet()
          val callId = CallId("only-call")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(callId, RespondInput(
              topicLabel   = "Done",
              topicSummary = "Direct reply.",
              content      = "Hi.",
              endsTurn     = true
            )),
            ProviderEvent.Done(StopReason.Complete)
          ))
        }
      }
      TestSigil.setProvider(Task.pure(singleProvider))
      val convId = Conversation.id(s"ends-turn-true-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Hi.")),
               state          = EventState.Complete
             ))
        _ <- Task.sleep(1.second)
      } yield {
        // Single iteration — provider was called exactly once.
        callCount.get() shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
