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

/**
 * Sigil bug #275 — repeated non-terminal tool calls must NOT trip
 * `AgentRunawayException` with the misleading "no tool call" attribution.
 * Pre-fix the runaway counter incremented on every iteration that didn't
 * produce a re-trigger event under `TriggerFilter`'s view, but a successful
 * non-terminal tool call's `ToolInvoke` (`role = Standard`, `participantId
 * = agent.id`) is excluded by both rules — so a productive multi-tool
 * turn looked identical to a turn where the model emitted no `tool_use`
 * at all.
 *
 * The fix adds an `iterationHadToolCall` flag set in the agent loop's
 * signal drain when any non-internal `ToolInvoke` flows past. The
 * `shouldIterate` decision short-circuits to `true` whenever that flag
 * is set — the next iteration reads the tool's result and decides what
 * to do. The cap (`maxAgentIterations`) still bounds runaway spirals.
 *
 * Drives a stubborn provider that emits `change_mode` (a non-terminal
 * tool already in the framework's static roster) every call. Asserts:
 *
 *   1. The loop reaches `maxAgentIterations` worth of provider calls
 *      (5 in this spec) BEFORE giving up — pre-fix it would have given
 *      up after 2 (noToolCallRetryLimit=1 in the old default), or after
 *      4 with the bumped default of 3.
 *   2. Any AgentRunaway failure attributes to `CapHit`, never `NoToolCall`.
 *
 * `change_mode` is settable to `"conversation"` (the current mode) — a
 * no-op switch, so the loop never actually changes state. The stubbornness
 * is deliberate: nothing breaks the cycle except the cap, which is the
 * legitimate path through the runaway.
 */
class NonTerminalToolLoopSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers
                              with org.scalatest.BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setMaxAgentIterations(5)

  override protected def afterAll(): Unit = {
    TestSigil.resetMaxAgentIterations()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "non-terminal-loop")

  /** Provider that emits a `change_mode` call per invocation — a
    * non-terminal tool, so the agent loop must rely on the
    * `iterationHadToolCall` flag (sigil #275) to recognise the
    * iteration was productive. Without the flag the loop misclassifies
    * each iteration as "no tool call" and burns the retry budget after
    * `noToolCallRetryLimit + 1` iterations. */
  private final class NonTerminalProvider extends Provider {
    val callCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n  = callCount.incrementAndGet()
      val id = CallId(s"non-terminal-$n")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(id, ChangeModeTool.schema.name.value),
        ProviderEvent.ToolCallComplete(id, ChangeModeInput(mode = "conversation")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = None, temperature = Some(0.0))
    )

  private def driveLoop(): Task[(NonTerminalProvider, Id[Conversation], List[sigil.event.Event])] = {
    val provider = new NonTerminalProvider
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"non-terminal-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Keep going.")),
             state          = EventState.Complete
           ))
      // Generous wait — the loop should run up to maxAgentIterations
      // before the cap-hit forced-synthesis turn + failure publish lands.
      _   <- Task.sleep(10.seconds)
      evs <- TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))
    } yield (provider, convId, evs)
  }

  "Non-terminal tool loop (sigil #275)" should {

    "advance past `noToolCallRetryLimit` iterations without firing the NoToolCall runaway" in {
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
        // Any AgentRunaway message that DID land must attribute to
        // CapHit, never NoToolCall — the cap-hit reason includes
        // "maxAgentIterations"; the narrowed NoToolCall reason
        // includes "zero `tool_use` blocks".
        val misattributions = runawayFailures.flatMap { msg =>
          val reason = msg.failureReason.getOrElse("")
          val ok = reason.contains("maxAgentIterations") && !reason.contains("zero `tool_use` blocks")
          if (ok) None else Some(reason)
        }
        withClue(s"calls=$calls; misattributions=$misattributions\n") {
          calls should be >= 5
          misattributions shouldBe empty
        }
      }
    }

    "land at least one ToolInvoke per iteration the model emitted a tool_use" in {
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
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
