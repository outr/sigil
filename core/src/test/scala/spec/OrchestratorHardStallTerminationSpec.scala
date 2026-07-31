package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.Model
import sigil.event.{AgentState, Event, Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import sigil.tool.core.RespondTool
import spec.GetMagicNumberTool

/**
 * Regression coverage for the model-independent hard-stall terminal.
 *
 * Every cooperative stall guard — the duplicate-call cap, the repeated-query
 * intercept, the progress checkpoint, the iteration-cap forced synthesis —
 * NUDGES the model to stop. A model that ignores all of them keeps emitting
 * the same tool call every iteration and grinds all the way to
 * `maxAgentIterations`, throwing `AgentRunawayException` (observed live:
 * Kimi-K2.6 called `get_workflow` with identical args until the cap, then
 * crashed). The duplicate-call cap detected the repeat early but only refused
 * dispatch; nothing shortened the loop.
 *
 * This drives the real agent loop with a provider that repeats an identical
 * call every iteration but COOPERATES with a `respond` once the roster is
 * narrowed to the respond family under forced synthesis. The framework should
 * detect the hard identical-call streak and force synthesis EARLY — so the
 * turn ends in a handful of iterations, not at `maxAgentIterations`.
 */
class OrchestratorHardStallTerminationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override protected val testTimeout: FiniteDuration = 2.minutes

  private val modelId: Id[Model] = Model.id("test", "hard-stall")
  TestSigil.testModel(modelId)

  private val MaxIterations = 20

  /** Repeats an identical `get_magic_number` call every iteration — a model
    * that ignores every cooperative stall guard. Under forced synthesis the
    * orchestrator narrows the roster to the respond family; the provider
    * detects that (no `get_magic_number` in the offered tools) and cooperates
    * with a `respond`, so a graceful terminal is reachable IF the framework
    * forces synthesis early. */
  private final class StuckRepeatProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.incrementAndGet()
      val callId = CallId(s"call-${rapid.Unique()}")
      val canCallMagic = input.tools.exists(_.schema.name.value == "get_magic_number")
      if (!canCallMagic)
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, "respond"),
          ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
            topicLabel   = "Wrapping up",
            topicSummary = "Summarising what was gathered",
            content      = "Here's what I found.",
            endsTurn     = true
          )),
          ProviderEvent.Done(StopReason.Complete)
        ))
      else
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, "get_magic_number"),
          ProviderEvent.toolCall(callId, GetMagicNumberTool)(GetMagicNumberInput()),
          ProviderEvent.Done(StopReason.Complete)
        ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = List(ToolName("get_magic_number")) ++ CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))
    )

  "A model that repeats one identical call, ignoring every cooperative guard" should {
    "be force-terminated early — not ground to maxAgentIterations" in {
      val provider = new StuckRepeatProvider
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setMaxAgentIterations(MaxIterations)
      val convId = Conversation.id(s"hard-stall-${rapid.Unique()}")
      val topic  = TopicEntry(id = Topic.id(s"t-${rapid.Unique()}"), label = "Stall", summary = "A stuck turn")
      val agent  = makeAgent()
      val conv   = Conversation(topics = List(topic), participants = List(agent), _id = convId)
      val task = for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = topic.id,
                 content        = Vector(ResponseContent.Text("Find the magic number for me.")),
                 state          = EventState.Complete
               ))
        _   <- waitForAgentTurn(convId, after = 0L, timeout = 90.seconds)
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val callCount = provider.calls.get()
        val magicInvokes = evs.count {
          case ti: sigil.event.ToolInvoke => ti.conversationId == convId && ti.toolName.value == "get_magic_number"
          case _ => false
        }
        withClue(s"provider called $callCount times, $magicInvokes get_magic_number invokes " +
          s"(maxAgentIterations=$MaxIterations): ") {
          // The framework must break the spin well before the iteration
          // cap. Pre-fix the loop grinds to maxAgentIterations; post-fix
          // the hard identical-call streak forces synthesis early.
          callCount should be <= 12
        }
      }
      task.guarantee(Task(TestSigil.resetMaxAgentIterations()))
    }
  }

  private def waitForAgentTurn(convId: Id[Conversation], after: Long, timeout: FiniteDuration): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = TestSigil.withDB(_.events.transaction(_.list)).flatMap { all =>
      val settled = all.exists {
        case a: AgentState if a.conversationId == convId && a.timestamp.value >= after && a.state == EventState.Complete => true
        case _ => false
      }
      if (settled) Task.unit
      else if (System.currentTimeMillis() < deadline) Task.sleep(200.millis).flatMap(_ => loop)
      else Task.unit
    }
    loop
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
