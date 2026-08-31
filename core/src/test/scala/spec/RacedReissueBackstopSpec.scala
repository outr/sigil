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
 * Sigil #407/#420 — a tool whose slow result keeps RACING past the frame
 * settles Pending, so its re-issues are excluded from the duplicate-call cap
 * (#354). A transient race is fine to retry, but a PERSISTENT racer would
 * re-issue unboundedly and never progress. After `maxRacedReissues` raced
 * identical re-issues this turn, the orchestrator stops inviting re-issue and
 * refuses with a non-escalating Failure that tells the TRUTH about the raced
 * call (#420 — the old unconditional "read the overflow file" pointed at a
 * file that small results never write, and its failure framing made agents
 * destructively undo writes that had succeeded):
 *
 *   - result settled → the refusal carries the actual rendered result inline
 *     and states plainly that the call SUCCEEDED (or names its failure);
 *   - still in flight → an honest still-finishing notice with an explicit
 *     "do not assume it failed / do not undo" — no ghost path, no
 *     hardcoded recovery tools the roster may not carry.
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
        ProviderEvent.toolCall(cid, FindCapabilityTool)(FindCapabilityInput(keywords = keywords)),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  /**
   * Pre-seed the agent's projection with `racedPriors` RACED (resulted=false)
   * identical invocations of `find_capability(keywords)`, scoped to this turn.
   */
  private def requestWithRacedPriors(convId: Id[Conversation], racedPriors: Int, keywords: String): ConversationRequest = {
    val hash = ToolInputCanonicalizer.argsHash(FindCapabilityInput(keywords = keywords))
    val preview = ToolInputCanonicalizer.argsPreview(FindCapabilityInput(keywords = keywords))
    val now = Timestamp(Nowish())
    val priors = (1 to racedPriors).toList.map(_ =>
      RecentToolInvocation(FindCapabilityTool.name, hash, preview, invokedAt = now, resulted = false))
    val projection = ParticipantProjection.empty(TestAgent, convId).copy(recentToolInvocations = priors)
    ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId, participantProjections = Map(TestAgent -> projection)),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools = Vector(FindCapabilityTool),
      chain = List(TestUser, TestAgent),
      // Scope the recent window to "this turn": priors stamped `now` count.
      turnStartedAt = Some(Timestamp(0L))
    )
  }

  private def redirectMessages(signals: List[Signal]): List[String] =
    signals.collect { case m: Message if m.role == MessageRole.Tool && m.isFailure => m }
      .flatMap(_.content.collect { case t: ResponseContent.Text => t.text })

  "Raced-reissue backstop (#407)" should {

    "hand the agent the settled SUCCESS result inline — never a ghost file, never a failure framing (#420 field shape)" in {
      // The field incident: set_metaobject (slow Shopify write, ~50-byte
      // result) raced; the old refusal claimed an overflow file that small
      // results never write, the agent inferred failure and DELETED the
      // entry it had just created, looping destructively for 16 minutes.
      val convId = Conversation.id(s"raced-settled-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val settledRow = ToolInvoke(
        toolName = FindCapabilityTool.name,
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        input = Some(FindCapabilityInput(keywords = "x")),
        output = sigil.tool.TextToolOutput("Created ingredient entry (id 63444255077, handle bacopa)."),
        outcome = sigil.event.ToolOutcome.Success,
        state = sigil.signal.EventState.Complete
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.withDB(_.eventsTransaction(convId)(_.upsert(settledRow)))
        signals <- Orchestrator.process(TestSigil, new FindCapStubProvider("x"), requestWithRacedPriors(convId, 2, "x"), conv).toList
      } yield {
        val texts = redirectMessages(signals)
        withClue(s"tool-failure messages: $texts\n") {
          val refusal = texts.find(_.contains("SUCCEEDED")).getOrElse(fail("no truthful refusal emitted"))
          // The actual result rides inline — the agent needs nothing else.
          refusal should include("Created ingredient entry (id 63444255077, handle bacopa).")
          refusal should include("Do NOT re-issue")
          refusal should include("undo")
          // No ghost file, no hardcoded recovery tools.
          refusal should not include ".sigil/output/"
          refusal should not include "read_file"
          // It's a redirect, NOT the duplicate-call escalation cap.
          texts.exists(_.contains("Refused to dispatch")) shouldBe false
        }
      }
    }

    "name the settled FAILURE instead of inviting a blind identical retry" in {
      val convId = Conversation.id(s"raced-failed-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val failedRow = ToolInvoke(
        toolName = FindCapabilityTool.name,
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        input = Some(FindCapabilityInput(keywords = "x")),
        outcome = sigil.event.ToolOutcome.Failure("rate limited by upstream", recoverable = true),
        state = sigil.signal.EventState.Complete
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.withDB(_.eventsTransaction(convId)(_.upsert(failedRow)))
        signals <- Orchestrator.process(TestSigil, new FindCapStubProvider("x"), requestWithRacedPriors(convId, 2, "x"), conv).toList
      } yield {
        val texts = redirectMessages(signals)
        withClue(s"tool-failure messages: $texts\n") {
          val refusal = texts.find(_.contains("COMPLETED WITH A FAILURE")).getOrElse(fail("no truthful refusal emitted"))
          refusal should include("rate limited by upstream")
          refusal should not include ".sigil/output/"
        }
      }
    }

    "say still-finishing when no settled row exists — no ghost file, no failure inference" in {
      // No durable settled invoke seeded: the call is genuinely in flight.
      val convId = Conversation.id(s"raced-inflight-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new FindCapStubProvider("x"), requestWithRacedPriors(convId, 2, "x"), conv).toList
      } yield {
        val texts = redirectMessages(signals)
        withClue(s"tool-failure messages: $texts\n") {
          val refusal = texts.find(_.contains("still")).getOrElse(fail("no still-finishing refusal emitted"))
          refusal should include("do NOT assume it failed")
          refusal should not include ".sigil/output/"
          refusal should not include "read_file"
          texts.exists(_.contains("Refused to dispatch")) shouldBe false
        }
      }
    }

    "allow a raced re-issue BELOW the bound (transient race, #354 preserved)" in {
      val convId = Conversation.id(s"raced-allow-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
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
