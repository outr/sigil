package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ParticipantProjection, RecentToolInvocation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.core.{FindCapabilityInput, FindCapabilityTool}
import sigil.tool.{ToolInputCanonicalizer, ToolName}
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Sigil #407 — a tool whose large/slow result keeps RACING past the frame
 * settles Pending, so its re-issues are excluded from the duplicate-call cap
 * (#354). A transient race is fine to retry, but a PERSISTENT racer would
 * re-issue unboundedly and never progress. After `maxRacedReissues` raced
 * identical re-issues this turn, the orchestrator stops inviting re-issue and
 * refuses with a non-escalating Failure that redirects the agent to the
 * externalized result (read/grep the overflow file).
 */
class RacedReissueBackstopSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  TestSigil.testModel(modelId)

  private class FindCapStubProvider(keywords: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(s"fc-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, FindCapabilityTool.name.value),
        ProviderEvent.ToolCallComplete(cid, FindCapabilityInput(keywords = keywords)),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  /** Pre-seed the agent's projection with `racedPriors` RACED (resulted=false)
    * identical invocations of `find_capability(keywords)`, scoped to this turn. */
  private def requestWithRacedPriors(convId: Id[Conversation], racedPriors: Int, keywords: String): ConversationRequest = {
    val hash    = ToolInputCanonicalizer.argsHash(FindCapabilityInput(keywords = keywords))
    val preview = ToolInputCanonicalizer.argsPreview(FindCapabilityInput(keywords = keywords))
    val now     = Timestamp(Nowish())
    val priors  = (1 to racedPriors).toList.map(_ =>
      RecentToolInvocation(FindCapabilityTool.name, hash, preview, invokedAt = now, resulted = false))
    val projection = ParticipantProjection.empty(TestAgent, convId).copy(recentToolInvocations = priors)
    ConversationRequest(
      conversationId     = convId,
      model              = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId, participantProjections = Map(TestAgent -> projection)),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools              = Vector(FindCapabilityTool),
      chain              = List(TestUser, TestAgent),
      // Scope the recent window to "this turn": priors stamped `now` count.
      turnStartedAt      = Some(Timestamp(0L))
    )
  }

  private def redirectMessages(signals: List[Signal]): List[String] =
    signals.collect { case m: Message if m.role == MessageRole.Tool && m.isFailure => m }
      .flatMap(_.content.collect { case t: ResponseContent.Text => t.text })

  "Raced-reissue backstop (#407)" should {

    "redirect to the overflow file after maxRacedReissues raced re-issues" in {
      // Default maxRacedReissues = 2 → seed 2 raced priors so this is the
      // re-issue that gets redirected instead of dispatched again.
      val convId = Conversation.id(s"raced-redirect-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new FindCapStubProvider("x"), requestWithRacedPriors(convId, 2, "x"), conv).toList
      } yield {
        val texts = redirectMessages(signals)
        withClue(s"tool-failure messages: $texts\n") {
          texts.exists(t => t.contains(".sigil/output/") && t.contains("read_file")) shouldBe true
          // It's a redirect, NOT the duplicate-call escalation cap.
          texts.exists(_.contains("Refused to dispatch")) shouldBe false
        }
      }
    }

    "allow a raced re-issue BELOW the bound (transient race, #354 preserved)" in {
      val convId = Conversation.id(s"raced-allow-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new FindCapStubProvider("x"), requestWithRacedPriors(convId, 1, "x"), conv).toList
      } yield {
        // Below the bound: no redirect, and the tool actually dispatches.
        redirectMessages(signals).exists(_.contains(".sigil/output/")) shouldBe false
        signals.collect { case t: ToolInvoke => t }.exists(_.toolName == FindCapabilityTool.name) shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
