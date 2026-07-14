package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.orchestrator.Orchestrator
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger

/**
 * Naked-text terminal handling. A plain-prose `end_turn` with no tool call
 * carries no explicit continue-vs-yield decision (`respond`'s required
 * `endsTurn` field), so the framework challenges the FIRST occurrence per
 * user turn with a `_turn_decision_required` diagnostic — the model must
 * either execute the work its prose announced or end the turn explicitly
 * via the respond family. A naked text on an already-challenged turn
 * commits as the terminal reply (the framework had its say — bounded, no
 * re-request loop), preserving the guarantee that a weak model's prose
 * answer still lands instead of producing duplicate never-committing
 * bubbles.
 */
class NakedTextTerminalSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "naked-text")
  TestSigil.testModel(modelId)

  /** A distinct model that has been observed to reject forced tool_choice
    * (#395 memo) — i.e. it runs on `auto`, so a plain-text answer is its
    * committed reply, not drift. Used by the #398 chat-completions test. */
  private val rejecterModelId: Id[Model] = Model.id("test", "naked-text-cc")
  TestSigil.testModel(rejecterModelId)

  private val answer = "Done — the working draft theme is now named \"Use Huron Test\"."

  /** Always answers with naked text + end_turn (Complete), no tool call —
    * exactly the auto-downgraded Fable/Mythos terminal turn. Counts calls so
    * the test can prove it isn't re-requested. */
  private final class NakedTextProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.incrementAndGet()
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ContentBlockDelta(CallId("naked-text-0"), answer),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Sigil #398 — the chat-completions wire (OpenAI-compatible, e.g. Fable via
    * OpenRouter) streams assistant prose as `TextDelta`, NOT `ContentBlockDelta`.
    * No Message is born during the stream, so #392's settle didn't fire and the
    * prose hit the `_plain_text_reply` drop. The Done handler must mint + commit
    * the Message from `plainTextBuffer` — but only for a forced-tool_choice
    * rejecter (a model on `auto`), distinguishing it from bug #75 drift. */
  private final class NakedTextChatCompletionsProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.incrementAndGet()
      Stream.emits(List[ProviderEvent](
        ProviderEvent.TextDelta(answer),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Emits an announcement as naked prose on the first call, then — after
    * the decision challenge — makes the explicit decision via
    * `respond(endsTurn = true)`. The compliant shape a real model takes. */
  private final class AnnounceThenRespondProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = calls.incrementAndGet()
      if (n == 1)
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ContentBlockDelta(CallId("announce-0"), "Next I'll rename the theme and report back."),
          ProviderEvent.Done(StopReason.Complete)
        ))
      else {
        val cid = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, "respond"),
          ProviderEvent.ToolCallComplete(cid, RespondInput(
            // Matches the active TestTopicEntry so `resolveTopicShift`
            // takes the same-topic fast path — this fake provider can't
            // answer a TopicClassifier consult.
            topicLabel   = TestTopicEntry.label,
            topicSummary = TestTopicEntry.summary,
            content      = "Renamed the theme as requested.",
            endsTurn     = true
          )),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      }
    }
  }

  private def makeAgent(mid: Id[Model] = modelId): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = mid,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings()
    )

  private def runUserTurn(provider: Provider, mid: Id[Model] = modelId): Task[Id[Conversation]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"naked-text-${rapid.Unique()}")
    val agent  = makeAgent(mid)
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Rename the theme.")),
             state          = EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId)
    } yield convId
  }

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "Naked-text terminal decision" should {

    "re-fire the challenge on a second narration with an escalated directive, then commit at the budget (#419)" in {
      val provider = new NakedTextProvider
      for {
        convId <- runUserTurn(provider)
        evs    <- eventsFor(convId)
      } yield {
        // Call 1: prose → challenge 1. Call 2: prose AGAIN — the second
        // bare narration is stronger stall evidence, so the challenge
        // re-fires with an escalated directive (a one-shot guard would
        // have committed it: the announce-then-stall failure, guaranteed
        // by the guard itself). Call 3: budget (2) spent — commits as the
        // terminal reply. Bounded — never a re-request spiral.
        provider.calls.get() shouldBe 3
        val challenges = evs.collect {
          case ti: sigil.event.ToolInvoke if ti.toolName.value == Orchestrator.TurnDecisionToolName => ti
        }
        challenges should have size 2
        // The second challenge escalates explicitly.
        val challengeTexts = evs.collect {
          case m: Message if m.role == MessageRole.Tool && m.origin.exists(o => challenges.exists(_._id == o)) =>
            m.content.collect { case t: ResponseContent.Text => t.text }.mkString
        }
        challengeTexts.count(_.contains("without acting")) shouldBe 1
        // Every prose message committed Complete and user-visible — the
        // streamed text is never lost, even from a model that ignores every
        // challenge.
        val replies = evs.collect {
          case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard
                          && m.state == EventState.Complete && m.isSuccess => m
        }
        replies should have size 3
        replies.foreach(
          _.content.collect { case t: ResponseContent.Text => t.text }.mkString should include("Use Huron Test"))
        succeed
      }
    }

    "challenge without minting on the chat-completions wire, then commit the repeat (forced-tool_choice rejecter)" in {
      // The model ran on `auto` (its forced tool_choice was downgraded),
      // so its plain-text reply is a genuine answer — not drift.
      Provider.recordForcedToolChoiceRejection(rejecterModelId)
      val provider = new NakedTextChatCompletionsProvider
      for {
        convId <- runUserTurn(provider, rejecterModelId)
        evs    <- eventsFor(convId)
      } yield {
        provider.calls.get() shouldBe 3
        val challenges = evs.collect {
          case ti: sigil.event.ToolInvoke if ti.toolName.value == Orchestrator.TurnDecisionToolName => ti
        }
        challenges should have size 2
        // The buffered prose was NOT minted on the challenged calls —
        // only the past-budget repeat committed, so the user sees exactly
        // one bubble. The challenge diagnostics carried the dropped text
        // for the model to re-wrap.
        val replies = evs.collect {
          case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard
                          && m.state == EventState.Complete && m.isSuccess => m
        }
        replies should have size 1
        replies.head.content.collect { case t: ResponseContent.Text => t.text }.mkString should include("Use Huron Test")
        // The prose was NOT routed to the `_plain_text_reply` drop diagnostic.
        evs.collect { case ti: sigil.event.ToolInvoke if ti.toolName.value == "_plain_text_reply" => ti } shouldBe empty
      }
    }

    "let a compliant model answer the challenge with an explicit respond" in {
      val provider = new AnnounceThenRespondProvider
      for {
        convId <- runUserTurn(provider)
        evs    <- eventsFor(convId)
      } yield {
        // Call 1: announcement prose → challenged. Call 2: the model makes
        // the explicit decision — respond(endsTurn = true). No third call.
        provider.calls.get() shouldBe 2
        evs.collect {
          case ti: sigil.event.ToolInvoke if ti.toolName.value == Orchestrator.TurnDecisionToolName => ti
        } should have size 1
        val respondInvokes = evs.collect {
          case ti: sigil.event.ToolInvoke if ti.toolName.value == "respond" => ti
        }
        respondInvokes should have size 1
        // The final answer landed via respond's content (parsed markdown).
        val replies = evs.collect {
          case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard
                          && m.state == EventState.Complete && m.isSuccess => m
        }
        replies.map(_.content.mkString).exists(_.contains("Renamed the theme")) shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
