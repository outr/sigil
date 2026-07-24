package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, MessageVisibility, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Sigil #359 — when a weak model fabricates a tool arg (e.g.
 * `disposition: "ResponseDisposition.InProgress"` on a `respond`), the
 * arg-decode failure must reach the AGENT as a recoverable Tool failure
 * it can correct on its next iteration — NOT vanish, leaving the agent
 * to re-fire on unchanged context.
 *
 * The real fix for #359 is honoring Scala defaults in the tool schema
 * (`WireSurfaceSpec`) so `disposition` is no longer `required` and the
 * model is never forced to fabricate it. These tests pin the framework's
 * end-to-end behavior for any *residual* decode failure: the orchestrator
 * routes it to the agent, it persists as an agent-visible frame, and it
 * renders into the next-iteration wire prompt.
 *
 * Mirrors the OrphanProviderErrorSpec harness: a fake provider scripts a
 * `ProviderEvent` stream and we assert on the resulting `Signal`s.
 */
class RespondDecodeFailureRoutingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model-359")

  private val decodeError =
    "Failed to parse args for tool respond: RWException: ResponseDisposition has no case with name: " +
      "ResponseDisposition.InProgress. Expected shape: { ... }."

  /**
   * Provider scripting a `respond` tool call whose args fail to decode —
   * llama.cpp's grammar-constrained tool-call-only shape (no streamed
   * content), the actual Sage case.
   */
  private class RespondDecodeFailureProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(
        ProviderEvent.ToolCallStart(CallId("call-359"), "respond"),
        ProviderEvent.Error(decodeError),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
  }

  /**
   * Same failure, but the respond content pre-streams as `ContentBlockDelta`
   * (a Message is born) — the frontier-provider shape. The streamed
   * placeholder must NOT settle into a user-facing dead-end.
   */
  private class StreamedRespondDecodeFailureProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("call-360")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, "respond"),
        ProviderEvent.ContentBlockDelta(cid, "I'll help you connect "),
        ProviderEvent.Error(decodeError),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def requestFor(convId: Id[Conversation], turnInput: TurnInput): ConversationRequest =
    ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = turnInput,
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent),
      tools = CoreTools.all
    )

  /**
   * The agent's retry trigger: a Tool-role Message carrying a recoverable
   * Failure, scoped to agents.
   */
  private def agentFailureTriggers(signals: List[Signal]): List[Message] =
    signals.collect {
      case m: Message
          if m.role == MessageRole.Tool &&
            (m.disposition match {
              case sigil.event.MessageDisposition.Failure(true, _) => true
              case _ => false
            }) => m
    }

  "Orchestrator respond decode-failure routing (sigil #359)" should {

    "route a respond arg-decode failure to the agent as a recoverable Tool failure" in {
      val convId = Conversation.id("respond-decode-fail-route")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val task = for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(
          TestSigil,
          new RespondDecodeFailureProvider,
          requestFor(convId, TurnInput(conversationId = convId)),
          conv
        ).toList
      } yield signals
      task.map { signals =>
        withClue(s"signals:\n${signals.map(s => "  " + s.getClass.getSimpleName + ": " + s.toString.take(160)).mkString("\n")}\n") {
          val triggers = agentFailureTriggers(signals)
          triggers should not be empty
          triggers.head.visibility shouldBe MessageVisibility.Agents
          signals.collect {
            case d: ToolDelta if d.outcome match {
                  case Some(ToolOutcome.Failure(_, recoverable)) => recoverable
                  case _ => false
                } => d
          } should not be empty
        }
      }
    }

    "persist the failure as an agent-visible frame that renders into the next-iteration prompt" in {
      val convId = Conversation.id("respond-decode-fail-publish")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val userMsg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("Connect my project")),
        state = EventState.Complete
      )
      val provider = sigil.provider.anthropic.AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(userMsg)
        signals <- Orchestrator.process(
          TestSigil,
          new RespondDecodeFailureProvider,
          requestFor(convId, TurnInput(conversationId = convId)),
          conv
        ).toList
        _ <- signals.foldLeft(Task.unit)((acc, s) => acc.flatMap(_ => TestSigil.publish(s)))
        events <- TestSigil.withDB(_.conversationEvents(convId))
        // The next iteration's actual prompt: curate + render through a
        // provider, exactly as buildContext → the wire does.
        turnInput <- TestSigil.curate(convId, modelId, List(TestUser, TestAgent))
      } yield {
        val agentFrames = events
          .filter(_.conversationId == convId)
          .flatMap(_.contextFrame.toList)
          .filter(f => TestSigil.visibilityAllows(f.visibility, TestAgent))
        withClue(s"agent-visible frames:\n${agentFrames.map("  " + _.toString.take(200)).mkString("\n")}\n") {
          agentFrames.map(_.toString).mkString("\n").toLowerCase should include("provider error")
        }
        val httpReq = provider.requestConverter(requestFor(convId, turnInput)).sync()
        val wire = httpReq.content match {
          case Some(c: spice.http.content.StringContent) => c.value
          case _ => ""
        }
        withClue(s"wire prompt:\n${wire.take(4000)}\n") {
          wire.toLowerCase should include("provider error")
        }
      }
    }

    "not dead-end the user with a streamed respond placeholder on decode failure" in {
      val convId = Conversation.id("respond-decode-fail-streamed")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val task = for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(
          TestSigil,
          new StreamedRespondDecodeFailureProvider,
          requestFor(convId, TurnInput(conversationId = convId)),
          conv
        ).toList
      } yield signals
      task.map { signals =>
        withClue(s"signals:\n${signals.map(s => "  " + s.getClass.getSimpleName + ": " + s.toString.take(160)).mkString("\n")}\n") {
          // The agent still gets its recoverable trigger.
          agentFailureTriggers(signals) should not be empty
          // No user-facing "failed to produce a valid reply" dead-end.
          signals.collect {
            case d: sigil.signal.MessageDelta
                if d.contentReplacement.exists(_.exists(_.toString.contains("failed to produce a valid reply"))) => d
          } shouldBe empty
          // The streamed placeholder DOES still settle (typing stops) —
          // Complete + recoverable Failure + empty content (collapsed).
          val placeholderSettle = signals.collect {
            case d: sigil.signal.MessageDelta
                if d.state.contains(EventState.Complete) &&
                  (d.disposition match {
                    case Some(sigil.event.MessageDisposition.Failure(true, _)) => true
                    case _ => false
                  }) => d
          }
          placeholderSettle should not be empty
          placeholderSettle.head.contentReplacement shouldBe Some(Vector.empty)
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
