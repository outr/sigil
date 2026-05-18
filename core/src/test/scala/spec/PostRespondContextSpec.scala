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
  ProviderEvent, ProviderMessage, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondFieldInput, RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Repro for sigil bug #71 — claim is that after a respond-family
 * terminal call settles and the user replies, the next agent
 * iteration's wire request collapses to msgs=2 (system + greeting-
 * style wrapper) instead of carrying the prior turn's frames.
 *
 * This spec drives the literal scenario:
 *   1. User asks an initial question.
 *   2. Agent emits `respond_field` (terminal, MessageRole.Tool).
 *   3. Loop settles, lock releases.
 *   4. User asks a follow-up.
 *   5. Loop fires the next iteration; agent emits `respond`.
 *
 * The custom provider records every [[ProviderCall]] it receives.
 * Asserting iteration-2's `messages.size` distinguishes:
 *   - msgs >= 3 → bug #71 isn't reproducible from core Sigil; the
 *     wire-log msgs=2 the user observed must be the topic-classifier
 *     consult call, not the agent's main iteration.
 *   - msgs == 2 → bug #71 confirmed; localised to whatever path
 *     produces the empty/wrapped frame vector for the second
 *     iteration's request.
 */
class PostRespondContextSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "post-respond-model")

  /**
   * Provider that records ProviderCalls + scripts two iterations:
   *   - Call 1: emit `respond_field` (terminal — settles the loop).
   *   - Call 2: emit `respond` (terminal — settles the second loop).
   */
  private class RecordingTwoIterProvider extends Provider {
    val calls: ConcurrentLinkedQueue[ProviderCall] = new ConcurrentLinkedQueue()
    private val callCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.add(input)
      val n = callCount.incrementAndGet()
      val callId = CallId(s"call-$n")
      val events: List[ProviderEvent] =
        if (n == 1)
          List(
            ProviderEvent.ToolCallStart(callId, "respond_field"),
            ProviderEvent.ToolCallComplete(
              callId,
              RespondFieldInput(label = "Project Ready", value = "true")
            ),
            ProviderEvent.Done(StopReason.Complete)
          )
        else
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(
              callId,
              RespondInput(topicLabel = "Overview", topicSummary = "Project overview", content = "Here's an overview.", endsTurn = true)
            ),
            ProviderEvent.Done(StopReason.Complete)
          )
      Stream.emits(events)
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = ToolName("respond_field") :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  "Sigil.runAgentLoop after respond-family terminal (bug #71 repro)" should {

    "carry prior-turn frames into the second iteration's wire request" in {
      val provider = new RecordingTwoIterProvider
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"post-respond-${rapid.Unique()}")
      val agent = makeAgent()
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("Set up project ready field.")),
          state = EventState.Complete
        ))
        _ <- Task.sleep(1500.millis) // wait for iteration 1 to settle
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("Can you give me an overview of this project?")),
          state = EventState.Complete
        ))
        _ <- Task.sleep(1500.millis) // wait for iteration 2 to settle
      } yield {
        val recorded = provider.calls.iterator().asScala.toList
        withClue(
          s"Provider received ${recorded.size} call(s); expected 2 — iteration 1 (respond_field) + iteration 2 (respond after user reply).") {
          recorded.size should be >= 2
        }

        // Print iteration-1 + iteration-2 shapes so the diagnostic
        // anchors what each call carries.
        recorded.zipWithIndex.foreach { case (call, idx) =>
          val shape = call.messages.map {
            case _: ProviderMessage.User => "user"
            case _: ProviderMessage.Assistant => "assistant"
            case _: ProviderMessage.ToolResult => "tool_result"
            case _: ProviderMessage.System => "system"
            case other => other.getClass.getSimpleName
          }.mkString(", ")
          info(s"Iteration ${idx + 1}: ${call.messages.size} messages [$shape]")
        }

        val iter2 = recorded(1)
        withClue(s"Iteration 2's wire request had ${iter2.messages.size} messages.") {
          // The minimum healthy shape after a respond_field + user reply:
          //   - user msg 1 ("Set up project ready field")
          //   - assistant tool call (respond_field) and tool result
          //   - user msg 2 ("Can you give me an overview...")
          // That's at least 3 messages. msgs == 1 (just the latest user
          // message) would confirm bug #71. msgs >= 3 disproves it.
          iter2.messages.size should be >= 3
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
