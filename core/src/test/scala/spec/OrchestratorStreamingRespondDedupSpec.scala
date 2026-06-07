package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Regression coverage for the streaming-respond duplicate-delivery gap.
 *
 * A small/local model that streams `respond(endsTurn = false)` keeps the
 * turn open (the framework's bug-#74 continuation lever), then — instead of
 * progressing with a real tool call — re-emits the SAME respond content on
 * the next iteration. The streaming-respond settle path bypasses the
 * duplicate-call cap that guards atomic dispatch, so the user receives the
 * identical message twice (observed live: Sage's "I'll connect to your
 * project… let me first check this directory" delivered on two consecutive
 * iterations, the second degenerating into a provider error).
 *
 * This drives the real agent loop with a scripted provider that repeats an
 * identical streaming respond and asserts exactly ONE user-visible message
 * settles — the in-turn duplicate is suppressed and the spin ends.
 */
class OrchestratorStreamingRespondDedupSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "streaming-respond-dedup")
  TestSigil.testModel(modelId)

  private val RepeatedContent =
    "I'll connect to your project at `/home/u/projects/demo`. Let me first check what's in this directory."

  /** Streams an identical `respond` every iteration. The first iteration
    * sets `endsTurn = false` to keep the turn open (reproducing the
    * continuation that exposes the bug); later iterations end the turn so a
    * pre-fix run terminates at a bounded count instead of running away. */
  private final class RepeatingStreamingRespondProvider extends Provider {
    private val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = calls.incrementAndGet()
      val callId = CallId(s"call-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "respond"),
        ProviderEvent.ContentBlockStart(callId, "Markdown", None),
        ProviderEvent.ContentBlockDelta(callId, RepeatedContent),
        ProviderEvent.ToolCallComplete(callId, RespondInput(
          topicLabel   = "Connect Project",
          topicSummary = "Connecting the user's project",
          content      = RepeatedContent,
          endsTurn     = n >= 3
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
      generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))
    )

  "Streaming respond" should {
    "deliver an in-turn repeated respond to the user only once" in {
      TestSigil.setProvider(Task.pure(new RepeatingStreamingRespondProvider))
      val convId  = Conversation.id(s"stream-dedup-${rapid.Unique()}")
      val topic   = TopicEntry(id = Topic.id(s"t-${rapid.Unique()}"), label = "Connect", summary = "Connecting a project")
      val agent   = makeAgent()
      val conv    = Conversation(topics = List(topic), participants = List(agent), _id = convId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = topic.id,
                 content        = Vector(ResponseContent.Text("Connect my project at /home/u/projects/demo")),
                 state          = EventState.Complete
               ))
        _   <- Task.sleep(4.seconds)
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val agentMessages = evs.collect {
          case m: Message
            if m.conversationId == convId
              && m.participantId == agent.id
              && m.role == MessageRole.Standard
              && m.state == EventState.Complete
              && m.content.nonEmpty => m
        }
        withClue(s"user-visible agent messages: ${agentMessages.map(_.content.size).mkString(",")} " +
          s"(${agentMessages.size} total): ") {
          agentMessages.size shouldBe 1
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
