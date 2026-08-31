package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondOptionsTool, RespondTool}
import sigil.tool.model.{RespondInput, RespondOptionsInput, ResponseContent, SelectOption}
import spice.http.HttpRequest

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.duration.*

/**
 * A user message that reaches `publish` while the prior turn still
 * holds its claim must still start a turn. The agent ends its turn
 * with `respond_options`; the user's pick arrives in the window
 * between the loop's last trigger check and the claim release — its
 * own `tryFire` is declined by the live claim, so the release path
 * owns the recovery.
 *
 * The pick is stamped while the agent is mid-turn (the shape any
 * transport that builds the Message on request arrival produces) and
 * published as the turn ends. Both cases below use that same message;
 * only WHEN it is published differs.
 *
 *   1. Published after the claim's Idle/Complete `AgentStateDelta` —
 *      its own `tryFire` wins the free claim.
 *   2. Published before that delta, while the claim is still held —
 *      recovery is entirely the release path's job.
 */
class OptionPickTurnStartSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "option-pick")
  TestSigil.testModel(modelId)

  private val PickValue = "yes"
  private val SecondTurnReply = "Acknowledged the pick."

  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[_root_.sigil.participant.ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }

  /**
   * Turn 1 runs two iterations — a plain tool call, then the
   * `respond_options` that ends it. Every later call replies with
   * `respond`, so the second turn is observable as its reply.
   */
  final private class ScriptedProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.force {
      Task {
        val n = calls.incrementAndGet()
        val callId = CallId(s"call-$n")
        val events: List[ProviderEvent] = n match {
          case 1 => List(
              ProviderEvent.ToolCallStart(callId, GetMagicNumberTool.schema.name.value),
              ProviderEvent.toolCall(callId, GetMagicNumberTool)(GetMagicNumberInput()),
              ProviderEvent.Done(StopReason.ToolCall)
            )
          case 2 => List(
              ProviderEvent.ToolCallStart(callId, RespondOptionsTool.schema.name.value),
              ProviderEvent.toolCall(callId, RespondOptionsTool)(RespondOptionsInput(
                prompt = "Should I bind this workspace?",
                options = List(SelectOption("Yes", PickValue), SelectOption("No", "no")),
                allowMultiple = false
              )),
              ProviderEvent.Done(StopReason.ToolCall)
            )
          case _ => List(
              ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
              ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
                topicLabel = TestTopicEntry.label,
                topicSummary = TestTopicEntry.summary,
                content = SecondTurnReply,
                endsTurn = true
              )),
              ProviderEvent.Done(StopReason.ToolCall)
            )
        }
        Stream.emits(events)
      }
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames ++ List(ToolName("respond_options"), GetMagicNumberTool.name),
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def userMessage(convId: Id[Conversation], text: String): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete
    )

  private def newConversation(name: String): Task[Conversation] = {
    val convId = Conversation.id(s"$name-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def waitFor(timeout: FiniteDuration)(cond: => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (cond || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(50.millis).flatMap(_ => loop)
    loop
  }

  private def agentReplies(convId: Id[Conversation]): Task[List[String]] =
    TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.collect {
      case m: Message if m.conversationId == convId && m.participantId == TestAgent && m.role == MessageRole.Standard =>
        m.content.collect {
          case ResponseContent.Text(t) => t
          case ResponseContent.Markdown(t) => t
        }.mkString
    })

  /**
   * Stamp the pick the moment the agent's first tool call goes out —
   * the turn is live and its terminal iteration has not started, so
   * the pick predates the boundary the release path checks against.
   */
  private def stampPickMidTurn(convId: Id[Conversation],
                               pick: AtomicReference[Option[Message]]): Signal => Task[Unit] = {
    case ti: ToolInvoke if ti.conversationId == convId && ti.toolName == GetMagicNumberTool.name =>
      Task { pick.compareAndSet(None, Some(userMessage(convId, PickValue))); () }
    case _ => Task.unit
  }

  "an option pick published after the claim releases" should {
    "start the agent's next turn" in {
      val provider = new ScriptedProvider
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setMemoryExtractor(NoExtraction)
      val pick = new AtomicReference[Option[Message]](None)
      for {
        conv <- newConversation("pick-post-release")
        _ = TestSigil.setInboundGate(stampPickMidTurn(conv._id, pick))
        _ <- TestSigil.publish(userMessage(conv._id, "Bind the workspace."))
        _ <- TestSigil.awaitSettled(conv._id)
        _ = TestSigil.resetInboundGate()
        _ <- TestSigil.publish(pick.get().getOrElse(fail("the pick was never stamped")))
        _ <- waitFor(20.seconds)(provider.calls.get() >= 3)
        _ <- TestSigil.awaitSettled(conv._id)
        replies <- agentReplies(conv._id)
      } yield withClue(s"providerCalls=${provider.calls.get()} replies=$replies: ") {
        replies.count(_.contains(SecondTurnReply)) shouldBe 1
      }
    }
  }

  "an option pick published before the claim releases" should {
    "start the agent's next turn rather than stranding the message" in {
      val provider = new ScriptedProvider
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setMemoryExtractor(NoExtraction)
      val pick = new AtomicReference[Option[Message]](None)
      val released = new AtomicBoolean(false)
      for {
        conv <- newConversation("pick-pre-release")
        // The terminal Idle delta publishes from inside `releaseClaim`,
        // after the loop's last trigger check and before the claim is
        // dropped — publishing the pick here puts it squarely in the
        // check-then-release gap. The hold keeps the claim live long
        // enough for the pick's own `tryFire` to be declined.
        stamp = stampPickMidTurn(conv._id, pick)
        _ = TestSigil.setInboundGate {
          case d: AgentStateDelta
              if d.conversationId == conv._id
                && d.activity.contains(AgentActivity.Idle)
                && released.compareAndSet(false, true) =>
            Task(TestSigil.publish(pick.get().getOrElse(fail("the pick was never stamped"))).startUnit())
              .flatMap(_ => Task.sleep(1.second))
          case other => stamp(other)
        }
        _ <- TestSigil.publish(userMessage(conv._id, "Bind the workspace."))
        _ <- waitFor(20.seconds)(provider.calls.get() >= 3)
        _ = TestSigil.resetInboundGate()
        _ <- TestSigil.awaitSettled(conv._id)
        replies <- agentReplies(conv._id)
      } yield withClue(s"providerCalls=${provider.calls.get()} pickPublished=${released.get()} replies=$replies: ") {
        released.get() shouldBe true
        replies.count(_.contains(SecondTurnReply)) shouldBe 1
      }
    }
  }

  "an option pick the running loop picks up itself" should {
    "answer it exactly once" in {
      val provider = new ScriptedProvider
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setMemoryExtractor(NoExtraction)
      val published = new AtomicBoolean(false)
      val running = new AtomicBoolean(true)
      for {
        conv <- newConversation("pick-mid-turn")
        // Published while the turn is still iterating, so the loop's own
        // boundary check can claim it. Whichever path wins — the loop or
        // the release handoff — the pick must be answered once, never
        // twice.
        _ = TestSigil.signals
          .takeWhile(_ => running.get())
          .evalMap {
            case m: Message
                if m.conversationId == conv._id && m.participantId == TestAgent
                  && m.content.exists(_.isInstanceOf[ResponseContent.Options])
                  && published.compareAndSet(false, true) =>
              TestSigil.publish(userMessage(conv._id, PickValue))
            case _ => Task.unit
          }
          .drain
          .startUnit()
        _ <- TestSigil.publish(userMessage(conv._id, "Bind the workspace."))
        _ <- waitFor(20.seconds)(provider.calls.get() >= 3)
        _ <- TestSigil.awaitSettled(conv._id)
        // Give any spurious second turn room to start before asserting.
        _ <- Task.sleep(2.seconds)
        _ = running.set(false)
        _ <- TestSigil.awaitSettled(conv._id)
        replies <- agentReplies(conv._id)
      } yield withClue(s"providerCalls=${provider.calls.get()} pickPublished=${published.get()} replies=$replies: ") {
        published.get() shouldBe true
        replies.count(_.contains(SecondTurnReply)) shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
