package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, FindCapabilityInput, FindCapabilityTool, RespondTool}
import sigil.tool.model.{RespondContent, RespondInput, ResponseContent}
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #159 — when the agent re-issues a
 * `find_capability` call with identical (normalized) keywords within
 * the same user turn, the orchestrator intercepts the duplicate
 * dispatch and emits a synthetic `_repeated_query_intercept`
 * ToolInvoke + a paired Tool-role Failure directing the agent to
 * refine the query or pick a different result from the prior hits.
 *
 * Loop safety mirrors the refusal-challenge intercept (sigil bug
 * #126): once per user turn. If the agent re-issues the same query
 * a THIRD time after the intercept, the call passes through (the
 * framework already had its say).
 */
class OrchestratorRepeatedQuerySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "repeated-query-spec")

  /** Provider that scripts a sequence of `find_capability` /
    * `respond` calls. Each call increments an internal counter; the
    * `scripts` factory determines what to emit for call N. After the
    * scripted sequence ends, any additional call emits a terminal
    * `respond` to keep the agent loop from runaway. */
  private final class ScriptedProvider(scripts: PartialFunction[Int, List[ProviderEvent]]) extends Provider {
    private val callIndex = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = callIndex.incrementAndGet()
      val events = scripts.applyOrElse(n, (_: Int) => terminalRespond(n))
      Stream.emits(events)
    }
  }

  private def findCapability(callIdx: Int, keywords: String): List[ProviderEvent] = {
    val cid = CallId(s"find-$callIdx")
    List(
      ProviderEvent.ToolCallStart(cid, FindCapabilityTool.schema.name.value),
      ProviderEvent.ToolCallComplete(cid, FindCapabilityInput(keywords = keywords)),
      ProviderEvent.Done(StopReason.ToolCall)
    )
  }

  private def terminalRespond(callIdx: Int): List[ProviderEvent] = {
    val cid = CallId(s"respond-$callIdx")
    List(
      ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
      ProviderEvent.ToolCallComplete(cid, RespondInput(
        topicLabel   = "Done",
        topicSummary = "Repeated-query spec terminator.",
        content      = RespondContent.Text("Stopping after the framework's intercept guidance."),
        endsTurn     = true
      )),
      ProviderEvent.Done(StopReason.Complete)
    )
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runScenario(provider: Provider, convPrefix: String): Task[(Id[Conversation], List[Event])] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"$convPrefix-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _   <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Switch to medium complexity")),
               state          = EventState.Complete
             ))
      _   <- Task.sleep(4.seconds)
      evs <- TestSigil.withDB(_.events.transaction(_.list))
    } yield (convId, evs.filter(_.conversationId == convId).sortBy(_.timestamp.value))
  }

  private val keywords = "complexity pin adjust medium level"

  "Orchestrator repeated-query intercept (sigil bug #159)" should {

    "intercept a second find_capability with identical normalized keywords in the same turn" in {
      val provider = new ScriptedProvider({
        case 1 => findCapability(1, keywords)
        case 2 => findCapability(2, "  COMPLEXITY  PIN  ADJUST  medium  level  ") // same after normalization
      })
      runScenario(provider, "repeat-intercept").map { case (_, evs) =>
        val intercepts = evs.collect {
          case ti: ToolInvoke if ti.toolName.value == "_repeated_query_intercept" => ti
        }
        intercepts.size shouldBe 1

        val failures = evs.collect {
          case m: Message
            if m.role == MessageRole.Tool &&
               m.content.exists {
                 case ResponseContent.Failure(reason, _, _) =>
                   reason.toLowerCase.contains("find_capability") &&
                   reason.toLowerCase.contains("different keywords")
                 case _ => false
               } => m
        }
        failures should not be empty
        failures.head.origin shouldBe Some(intercepts.head._id)
      }
    }

    "NOT intercept the FIRST find_capability of a turn" in {
      val provider = new ScriptedProvider({
        case 1 => findCapability(1, keywords)
      })
      runScenario(provider, "no-intercept-first").map { case (_, evs) =>
        evs.collect { case ti: ToolInvoke if ti.toolName.value == "_repeated_query_intercept" => ti } shouldBe empty
      }
    }

    "NOT intercept a different-keywords find_capability" in {
      val provider = new ScriptedProvider({
        case 1 => findCapability(1, "complexity pin medium")
        case 2 => findCapability(2, "switch operating mode toolset") // different shape
      })
      runScenario(provider, "no-intercept-different").map { case (_, evs) =>
        evs.collect { case ti: ToolInvoke if ti.toolName.value == "_repeated_query_intercept" => ti } shouldBe empty
      }
    }

    "fire at most once per user turn — a THIRD identical query passes through" in {
      val provider = new ScriptedProvider({
        case 1 => findCapability(1, keywords)
        case 2 => findCapability(2, keywords) // first duplicate — intercepted
        case 3 => findCapability(3, keywords) // SECOND duplicate after intercept — passes
      })
      runScenario(provider, "once-per-turn").map { case (_, evs) =>
        evs.collect {
          case ti: ToolInvoke if ti.toolName.value == "_repeated_query_intercept" => ti
        }.size shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
