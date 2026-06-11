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
  ProviderStreamException, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil #387 — models that reject forced `tool_choice`
 * (Claude Fable 5 / Mythos 5 → HTTP 400 "tool_choice forces tool use
 * is not compatible with this model.") must not be unusable as agent
 * models. The provider self-heal detects the specific rejection and
 * retries the SAME call once with `tool_choice` downgraded to `Auto`.
 *
 * Two layers are pinned:
 *   - The pure detection predicate + [[ToolChoice.isForced]] classifier.
 *   - End-to-end: a fake provider that 400s on a forced choice and
 *     succeeds on `Auto`, asserting the retry goes out with `Auto`.
 */
class ForcedToolChoiceSelfHealSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "forced-tool-choice")
  TestSigil.testModel(modelId)

  private def rejection(message: String): ProviderStreamException =
    new ProviderStreamException(
      providerKey = "anthropic", code = 0, typ = "invalid_request_error",
      message_ = message, status = Some(400)
    )

  /** Fake provider that records the `tool_choice` of every call. On the
    * first call WHILE the choice is forced it raises the forced-tool-
    * choice 400; once the framework retries with `Auto` it emits a clean
    * `respond`. */
  private final class ForcedChoiceRejectsThenSucceeds extends Provider {
    val choices: java.util.concurrent.ConcurrentLinkedQueue[ToolChoice] =
      new java.util.concurrent.ConcurrentLinkedQueue[ToolChoice]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      choices.add(input.toolChoice)
      if (input.toolChoice.isForced) {
        Stream.force(Task.error(rejection(
          "tool_choice forces tool use is not compatible with this model."
        )))
      } else {
        val cid = CallId(s"respond-${choices.size()}")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.ToolCallComplete(
            cid,
            RespondInput(topicLabel = "T", topicSummary = "s", content = "ok", endsTurn = true)
          ),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings()
    )

  private def runUserTurn(provider: Provider): Task[Id[Conversation]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"self-heal-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Say hi.")),
             state          = EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId)
    } yield convId
  }

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "ToolChoice.isForced" should {
    "treat Required and Specific as forced; Auto and None as not" in {
      ToolChoice.Required.isForced shouldBe true
      ToolChoice.Specific(RespondTool.schema.name).isForced shouldBe true
      ToolChoice.Auto.isForced shouldBe false
      ToolChoice.None.isForced shouldBe false
      succeed
    }
  }

  "Provider.isForcedToolChoiceRejection" should {
    "match the Anthropic marker case-insensitively, including a wrapped cause" in {
      Provider.isForcedToolChoiceRejection(rejection(
        "tool_choice forces tool use is not compatible with this model."
      )) shouldBe true
      Provider.isForcedToolChoiceRejection(
        new RuntimeException("TOOL_CHOICE FORCES TOOL USE IS NOT COMPATIBLE with X")
      ) shouldBe true
      Provider.isForcedToolChoiceRejection(
        new RuntimeException("wrapper", rejection("tool_choice forces tool use is not compatible"))
      ) shouldBe true
      succeed
    }
    "not match unrelated provider errors" in {
      Provider.isForcedToolChoiceRejection(new RuntimeException("rate_limit_error: slow down")) shouldBe false
      Provider.isForcedToolChoiceRejection(rejection("overloaded_error")) shouldBe false
      Provider.isForcedToolChoiceRejection(new RuntimeException(null: String)) shouldBe false
      succeed
    }
  }

  "Provider forced-tool_choice self-heal (sigil #387)" should {
    "downgrade tool_choice to Auto and retry once when the model rejects forced tool use" in {
      val provider = new ForcedChoiceRejectsThenSucceeds
      for {
        convId <- runUserTurn(provider)
        evs    <- eventsFor(convId)
      } yield {
        val seen = provider.choices.asScala.toList
        // First call went out forced (the framework's default for a
        // tool-bearing turn); the retry went out as Auto.
        seen.headOption.exists(_.isForced) shouldBe true
        seen should contain(ToolChoice.Auto)
        // The forced rejection preceded the first Auto call (downgrade,
        // not an independent later turn that happened to be Auto).
        val firstAuto = seen.indexOf(ToolChoice.Auto)
        firstAuto should be > 0
        seen(firstAuto - 1).isForced shouldBe true
        // The retry's success reached the agent; no Failure bubble.
        val agentReplies = evs.collect {
          case m: Message if m.participantId == TestAgent && m.isSuccess && m.role == MessageRole.Standard => m
        }
        agentReplies should not be empty
        evs.collect { case m: Message if m.isFailure => m } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
