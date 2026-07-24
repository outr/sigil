package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*

/**
 * Regression for Sigil #257 — a turn that returns no tool call
 * (a transient hiccup, common with reasoning models that drop the
 * tool call after their reasoning block) must NOT immediately strip
 * the agent's roster to the respond family.
 *
 * The framework retries up to [[sigil.Sigil.noToolCallRetryLimit]]
 * times with the FULL roster intact — a plain re-prompt usually
 * self-corrects — and only falls back to the respond-only forced
 * synthesis once those retries are exhausted. Pre-fix, the first
 * no-tool-call response stripped the roster, turning one hiccup into
 * a guaranteed non-answer for any turn that needed tools.
 */
class NoToolCallRetrySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with org.scalatest.BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  // Sigil #273 bumped the default from 1 to 3; this spec's call-count
  // assertions were written for limit=1 and asserting on exact roster
  // shapes per call index. Pin to the old value so the assertions stay
  // stable; the budget bump's behaviour is covered by other specs.
  TestSigil.setNoToolCallRetryLimit(1)

  override protected def afterAll(): Unit = {
    TestSigil.resetNoToolCallRetryLimit()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "no-toolcall-retry")
  TestSigil.testModel(modelId)

  /**
   * Records each [[ProviderCall]] so the spec can inspect the roster
   * the framework sent on every turn.
   */
  final private class CallRecorder {
    val calls: atomic.AtomicReference[Vector[ProviderCall]] =
      new atomic.AtomicReference(Vector.empty)
    def record(input: ProviderCall): Unit = {
      calls.updateAndGet(_ :+ input)
      ()
    }
  }

  /**
   * Call 1 returns no tool call (a transient hiccup); every later
   * call emits a real `respond`.
   */
  final private class HiccupThenRespondProvider(recorder: CallRecorder) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      recorder.record(input)
      if (recorder.calls.get().size == 1)
        Stream.emit(ProviderEvent.Done(StopReason.Complete)) // no tool call
      else {
        val callId = CallId(s"retry-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
          ProviderEvent.ToolCallComplete(
            callId,
            RespondInput(topicLabel = "Done", topicSummary = "recovered", content = "all set", endsTurn = true)
          ),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  /**
   * Every call returns no tool call — the retry never recovers, so
   * the loop must eventually fall back to the respond-only forced
   * synthesis.
   */
  final private class AlwaysHiccupProvider(recorder: CallRecorder) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      recorder.record(input)
      Stream.emit(ProviderEvent.Done(StopReason.Complete)) // never a tool call
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runTurn(provider: Provider, convPrefix: String): Task[List[Event]] = {
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
        content = Vector(ResponseContent.Text("do the task")),
        state = EventState.Complete
      ))
      _ <- Task.sleep(6.seconds)
      evs <- TestSigil.withDB(_.events.transaction(_.list))
    } yield evs.filter(_.conversationId == convId)
  }

  private val respondFamily: Set[ToolName] = CoreTools.atomicContentToolNames

  /**
   * The provider calls that are agent-loop turns — i.e. carry the
   * agent's roster (which always includes `respond`). Filters out the
   * framework's one-shot consult calls (topic classification,
   * memory extraction) that also route through the stub provider.
   */
  private def agentLoopCalls(recorder: CallRecorder): Vector[ProviderCall] =
    recorder.calls.get().filter(_.tools.exists(_.schema.name.value == "respond"))

  "Sigil #257 — no-tool-call recovery" should {

    "retry with the FULL roster after a transient no-tool-call response" in {
      val recorder = new CallRecorder
      runTurn(new HiccupThenRespondProvider(recorder), "hiccup-recover").map { evs =>
        val calls = agentLoopCalls(recorder)
        withClue(s"expected exactly 2 agent-loop calls (hiccup + full-roster retry); got ${calls.size}: ") {
          calls.size shouldBe 2
        }
        // The retry (call 2) keeps the full roster — a non-respond
        // tool like `find_capability` is still present. Pre-fix it
        // would have been stripped to the respond family.
        val retryToolNames = calls(1).tools.map(_.schema.name.value).toSet
        withClue(s"retry roster was ${retryToolNames.mkString(", ")}: ") {
          retryToolNames should contain("find_capability")
        }
        // The agent recovered and produced its real reply.
        val agentReplies = evs.collect {
          case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard => m
        }
        agentReplies should not be empty
      }
    }

    "fall back to the respond-only forced synthesis once the retry limit is exhausted" in {
      val recorder = new CallRecorder
      runTurn(new AlwaysHiccupProvider(recorder), "hiccup-always").map { _ =>
        val calls = agentLoopCalls(recorder)
        // Call 1 = hiccup, call 2 = full-roster retry (also hiccups),
        // call 3 = forced synthesis with the roster stripped.
        withClue(s"expected 3 agent-loop calls; got ${calls.size}: ") {
          calls.size shouldBe 3
        }
        // Calls 1 and 2 carry the full roster.
        calls(0).tools.map(_.schema.name.value) should contain("find_capability")
        calls(1).tools.map(_.schema.name.value) should contain("find_capability")
        // Call 3 — the forced-synthesis turn — is stripped to the
        // respond family only.
        val forcedToolNames = calls(2).tools.map(_.schema.name).toSet
        withClue(s"forced roster was ${forcedToolNames.map(_.value).mkString(", ")}: ") {
          forcedToolNames.subsetOf(respondFamily) shouldBe true
          forcedToolNames should not be empty
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
