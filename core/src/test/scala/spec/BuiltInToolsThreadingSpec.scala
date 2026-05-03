package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.Sigil
import sigil.conversation.{ActiveSkillSlot, Conversation}
import sigil.db.Model
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  BuiltInTool,
  GenerationSettings,
  Instructions,
  Mode,
  Provider,
  ProviderCall,
  ProviderEvent,
  ProviderType,
  StopReason,
  ToolPolicy
}
import spice.http.HttpRequest

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*

/**
 * Regression for the BuiltInTools plumbing fix: `Sigil.defaultProcess`
 * must union [[AgentParticipant.builtInTools]] and the current
 * [[Mode.builtInTools]] into the [[ConversationRequest.builtInTools]]
 * passed to the provider.
 *
 * Pre-fix the orchestrator never set `builtInTools`, so native
 * provider-side capabilities (Anthropic web search, OpenAI Responses
 * web search, Google Gemini grounding) were unreachable through the
 * normal `Sigil.publish` agent loop.
 *
 * The spec arms a fake provider that captures the inbound `ProviderCall`,
 * fires a greet-on-join turn, and asserts the captured set matches the
 * expected union.
 */
class BuiltInToolsThreadingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProvider(Task.pure(CapturingProvider))

  private def freshConvId(suffix: String): Id[Conversation] =
    Conversation.id(s"builtins-$suffix-${rapid.Unique()}")

  private def agent(builtInTools: Set[BuiltInTool]): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = CapturingProvider.modelId,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      builtInTools = builtInTools,
      greetsOnJoin = true
    )

  private def awaitCapture(timeoutMs: Long = 5000): Task[Set[BuiltInTool]] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop: Task[Set[BuiltInTool]] =
      Option(CapturingProvider.lastBuiltIns.get()) match {
        case Some(s) => Task.pure(s)
        case None if System.currentTimeMillis() > deadline =>
          Task.error(new RuntimeException("CapturingProvider never received a request"))
        case None =>
          Task.sleep(25.millis).flatMap(_ => loop)
      }
    loop
  }

  "Sigil.defaultProcess" should {

    "thread agent.builtInTools straight through to ConversationRequest" in {
      CapturingProvider.reset()
      val convId = freshConvId("agent-only")
      val a = agent(builtInTools = Set(BuiltInTool.WebSearch))
      for {
        _    <- TestSigil.newConversation(createdBy = TestUser, participants = List(a), conversationId = convId)
        seen <- awaitCapture()
      } yield {
        seen should contain only BuiltInTool.WebSearch
      }
    }

    "union agent.builtInTools with the current Mode.builtInTools" in {
      CapturingProvider.reset()
      val convId = freshConvId("union")
      // Override the active mode for this conversation by passing one that
      // has its own builtInTools set. The framework's
      // ConversationMode.builtInTools defaults to empty, so we mint a
      // fresh Mode subtype just for this assertion.
      val a = DefaultAgentParticipant(
        id = TestAgent,
        modelId = CapturingProvider.modelId,
        instructions = Instructions(),
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        builtInTools = Set(BuiltInTool.ImageGeneration),
        greetsOnJoin = true
      )
      for {
        _    <- TestSigil.newConversation(createdBy = TestUser, participants = List(a),
                                          conversationId = convId, currentMode = WebResearchMode)
        seen <- awaitCapture()
      } yield {
        // ImageGeneration came from agent; WebSearch from mode — both must surface.
        seen should contain allOf (BuiltInTool.ImageGeneration, BuiltInTool.WebSearch)
        seen.size shouldBe 2
      }
    }

    "default to empty when neither agent nor mode opts in" in {
      CapturingProvider.reset()
      val convId = freshConvId("none")
      val a = agent(builtInTools = Set.empty)
      for {
        _    <- TestSigil.newConversation(createdBy = TestUser, participants = List(a), conversationId = convId)
        seen <- awaitCapture()
      } yield {
        seen shouldBe Set.empty[BuiltInTool]
      }
    }
  }
}

/**
 * Mode used to assert that `Mode.builtInTools` flows into the request.
 * Registered into the `Mode` poly via [[TestSigil]]'s `modes` override
 * (see `TestSigil.scala`).
 */
case object WebResearchMode extends Mode {
  override def name: String = "web-research"
  override def description: String = "Mode for web research scenarios."
  override def skill: Option[ActiveSkillSlot] = None
  override def tools: ToolPolicy = ToolPolicy.Standard
  override def builtInTools: Set[BuiltInTool] = Set(BuiltInTool.WebSearch)
}

/**
 * Stub provider that captures the inbound `ProviderCall` for
 * assertion, then emits a single `Done(StopReason.Complete)` so the
 * agent loop terminates without an LLM round-trip.
 */
private object CapturingProvider extends Provider {
  val modelId: Id[Model] = Model.id("capturing-stub")
  val callCount: AtomicInteger = new AtomicInteger(0)
  /** The set of [[BuiltInTool]]s the most recent inbound request carried.
    * Captured directly off the [[ProviderCall]] the framework passes to
    * `call` — `Sigil.defaultProcess` populates this from
    * `agent.builtInTools ++ mode.builtInTools`, so reading it here proves
    * the union threading. */
  val lastBuiltIns: AtomicReference[Set[BuiltInTool]] = new AtomicReference(null)

  def reset(): Unit = {
    callCount.set(0)
    lastBuiltIns.set(null)
  }

  override def `type`: ProviderType = ProviderType.LlamaCpp
  override def models: List[Model] = Nil
  override protected def sigil: Sigil = TestSigil

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    Task.error(new UnsupportedOperationException("CapturingProvider"))

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    callCount.incrementAndGet()
    lastBuiltIns.set(input.builtInTools)
    Stream.emit(ProviderEvent.Done(StopReason.Complete))
  }
}
