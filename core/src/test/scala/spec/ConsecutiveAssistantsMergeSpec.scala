package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, FrameBuilder}
import sigil.db.Model
import sigil.event.{Message, TopicChange, TopicChangeKind}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderMessage,
  ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil bug #74 dup — multi-respond turns
 * (`endsTurn = false` then `endsTurn = true`) used to produce two
 * consecutive `role=assistant` messages that OpenAI-compatible
 * providers reject with HTTP 400. Two changes prevent it:
 *
 *   - [[FrameBuilder]] no longer emits a `ContextFrame.System` for
 *     `TopicChange` events. Topic = metadata; the system prompt's
 *     "Current topic:" / "Previous topics:" section already
 *     conveys current state. Without the mid-array System frame,
 *     [[sigil.provider.llamacpp.LlamaCppProvider.foldMidArraySystems]]
 *     can't fold a `[system: Topic switched to: …]` prefix onto an
 *     assistant message.
 *
 *   - [[Provider.renderFrames]] post-merges consecutive content-only
 *     [[ProviderMessage.Assistant]] entries with a `\n\n` separator.
 *     Defense in depth — any future cause of consecutive assistants
 *     (not just multi-respond) gets handled at the wire-shape gate.
 *     Tool-call assistants pass through untouched.
 */
class ConsecutiveAssistantsMergeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "FrameBuilder.computeFrame" should {
    "produce no frame for a TopicChange (metadata, not history)" in {
      val tc = TopicChange(
        kind           = TopicChangeKind.Switch(previousTopicId = sigil.conversation.Topic.id("prev")),
        newLabel       = "Admin services",
        newSummary     = "Evaluating the admin module.",
        participantId  = TestAgent,
        conversationId = Conversation.id("frame-test"),
        topicId        = sigil.conversation.Topic.id("admin"),
        state          = EventState.Complete
      )
      Task(FrameBuilder.computeFrame(tc) shouldBe None)
    }
  }

  "Sigil.runAgentLoop after a multi-respond turn (#74 dup)" should {

    "produce a wire request with no two consecutive role=assistant entries" in {
      val provider = new RecordingTwoRespondProvider
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"two-respond-${rapid.Unique()}")
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
        // Trigger a third turn so we get a wire request that includes
        // both prior responds in its history. The third call is what
        // proves no consecutive assistants land in the request body.
        _ <- Task.sleep(2.seconds)
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Continue.")),
               state          = EventState.Complete
             ))
        _ <- Task.sleep(1500.millis)
      } yield {
        val recorded = provider.calls.iterator().asScala.toList
        // Walk every recorded request and assert no two consecutive
        // content-only assistant entries appear.
        recorded.zipWithIndex.foreach { case (call, idx) =>
          val pairs = call.messages.sliding(2).toList.zipWithIndex
          pairs.foreach { case (Vector(a, b), i) =>
            (a, b) match {
              case (pa: ProviderMessage.Assistant, pb: ProviderMessage.Assistant)
                if pa.toolCalls.isEmpty && pb.toolCalls.isEmpty =>
                  fail(s"Call $idx: messages[$i] and messages[${i + 1}] are both content-only assistant — wire spec violation. " +
                       s"Content: '${pa.content.take(60)}…' / '${pb.content.take(60)}…'")
              case _ => ()
            }
            case _ => ()
          }
        }
        succeed
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }

  // ---- fixtures ----

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = Model.id("test", "merge-spec-model"),
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  /** Provider that scripts: turn 1 → respond(endsTurn=false) →
    * respond(endsTurn=true); turn 2 → respond(endsTurn=true).
    * Records every ProviderCall so the spec can inspect the
    * messages array sent on every turn. */
  private class RecordingTwoRespondProvider extends Provider {
    val calls: java.util.concurrent.ConcurrentLinkedQueue[ProviderCall] =
      new java.util.concurrent.ConcurrentLinkedQueue()
    private val callCount = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.add(input)
      val n = callCount.incrementAndGet()
      val callId = CallId(s"call-$n")
      val payload = n match {
        case 1 =>
          // First respond — kicks off iteration with a status pulse
          // that triggers a TopicChange (different topicLabel) and
          // continues the loop.
          RespondInput(
            topicLabel   = "Admin services in widge-server",
            topicSummary = "Evaluating the admin services.",
            content      = RespondContent.Text("Found 500 matches. I'll refine to focus on admin service definitions."),
            endsTurn     = false
          )
        case 2 =>
          // Second respond in the SAME turn — final answer.
          RespondInput(
            topicLabel   = "Admin services in widge-server",
            topicSummary = "Final breakdown of admin services.",
            content      = RespondContent.Text("I found 500 matches for \"admin\" in widge-server. Here's the breakdown: …"),
            endsTurn     = true
          )
        case _ =>
          // Subsequent turn — final answer; this is the call whose
          // messages array is the wire-spec validation target.
          RespondInput(
            topicLabel   = "Admin services in widge-server",
            topicSummary = "Continuing the analysis.",
            content      = RespondContent.Text("Continuing."),
            endsTurn     = true
          )
      }
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callId, payload),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }
}
