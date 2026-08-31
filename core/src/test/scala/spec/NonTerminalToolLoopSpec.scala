package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, CoreTools}
import sigil.tool.model.{ChangeModeInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*

class NonTerminalToolLoopSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with org.scalatest.BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setMaxAgentIterations(5)

  override protected def afterAll(): Unit = {
    TestSigil.resetMaxAgentIterations()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "non-terminal-loop")
  TestSigil.testModel(modelId)

  /**
   * Provider that emits a `change_mode` call per invocation — a
   * non-terminal tool, so the agent loop must rely on the
   * `iterationHadToolCall` flag (sigil #275) to recognise the
   * iteration was productive. Without the flag the loop misclassifies
   * each iteration as "no tool call" and burns the retry budget after
   * `noToolCallRetryLimit + 1` iterations.
   */
  final private class NonTerminalProvider extends Provider {
    val callCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = callCount.incrementAndGet()
      val id = CallId(s"non-terminal-$n")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(id, ChangeModeTool.schema.name.value),
        ProviderEvent.toolCall(id, ChangeModeTool)(ChangeModeInput(mode = "coding")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = None, temperature = Some(0.0))
    )

  private def driveLoop(): Task[(NonTerminalProvider, Id[Conversation], List[sigil.event.Event])] = {
    val provider = new NonTerminalProvider
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"non-terminal-${rapid.Unique()}")
    val agent = makeAgent()
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Keep going.")),
        state = EventState.Complete
      ))
      // Generous wait — the loop should run up to maxAgentIterations
      // before the cap-hit forced-synthesis turn + failure publish lands.
      _ <- TestSigil.awaitSettled(convId)
      evs <- TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))
    } yield (provider, convId, evs)
  }

  "Non-terminal tool loop (sigil #275)" should {

    "advance past `noToolCallRetryLimit` iterations without firing the NoToolCall runaway" in
      driveLoop().map { case (provider, _, evs) =>
        val calls = provider.callCount.get()
        val runawayFailures = evs.collect {
          case m: Message
              if m.isFailure && m.failureReason.exists(_.contains("AgentRunaway")) => m
        }
        // Pre-fix: callCount would top out at noToolCallRetryLimit+1 (4
        // under the bumped default, 2 under the original) because every
        // non-terminal tool call was misclassified as "no tool call".
        // With the fix the loop runs to maxAgentIterations (5) before
        // the cap forces synthesis.
        //
        // Any AgentRunaway message that DID land must never attribute to
        // NoToolCall — its narrowed reason includes "zero `tool_use`
        // blocks", and every iteration here emitted one. Which terminal
        // guard ended the turn instead (the iteration cap, or the
        // duplicate-call cap's refusal bound — this model re-issues one
        // identical call) is not what this spec is about.
        val misattributions = runawayFailures.flatMap { msg =>
          val reason = msg.failureReason.getOrElse("")
          if (reason.contains("zero `tool_use` blocks")) Some(reason) else None
        }
        withClue(s"calls=$calls; misattributions=$misattributions\n") {
          calls should be >= 5
          misattributions shouldBe empty
        }
      }

    "land at least one ToolInvoke per iteration the model emitted a tool_use" in
      driveLoop().map { case (provider, _, evs) =>
        val invokes = evs.collect {
          case ti: ToolInvoke if ti.toolName.value == ChangeModeTool.schema.name.value => ti
        }
        // One invoke per provider call. Confirms the orchestrator
        // dispatched each iteration's tool_use rather than dropping it.
        withClue(s"calls=${provider.callCount.get()} invokes=${invokes.size}\n") {
          invokes.size should be >= provider.callCount.get()
        }
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
