package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, ParticipantProjection, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions}
import sigil.provider.anthropic.AnthropicProvider
import sigil.tool.core.CoreTools

/**
 * Sigil #390 — the AnthropicProvider drives temperature/top_p off the
 * model's catalog `supported_parameters` (via `Sigil.supportsParameter`),
 * the way the OpenAI provider already does. The Claude 5 generation
 * (Fable 5 / Mythos 5) removed sampling params; their catalog omits them,
 * so the provider must NOT put them on the wire — proactively, not via a
 * round-trip-wasting 400 + self-heal.
 */
class AnthropicSamplingCapabilitySpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)

  /**
   * Register a model with the given `supportedParameters` set.
   */
  private def register(id: Id[Model], params: Set[String]): Unit = {
    val m = TestSigil.testModel(id).copy(supportedParameters = params)
    TestSigil.cache.merge(List(m)).sync()
  }

  private def renderedBody(modelId: Id[Model]): fabric.Json = {
    val convId = sigil.conversation.Conversation.id(s"cap-${rapid.Unique()}")
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.cache.find(modelId).getOrElse(fail("model not registered")),
      instructions = Instructions(),
      turnInput = TurnInput(
        conversationId = convId,
        frames = Vector(ContextFrame.Text("hi", TestUser, Id[Event]("seed"))),
        participantProjections = Map(TestAgent -> ParticipantProjection.empty(TestAgent, convId))
      ),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      // The caller sets a sampling param — the thing the catalog may forbid.
      generationSettings = GenerationSettings(temperature = Some(0.7), topP = Some(0.9), maxOutputTokens = Some(50)),
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )
    val httpReq = provider.requestConverter(request).sync()
    httpReq.content match {
      case Some(c: spice.http.content.StringContent) => fabric.io.JsonParser(c.value)
      case other => fail(s"expected string body, got $other")
    }
  }

  "AnthropicProvider sampling-param catalog gate (sigil #390)" should {

    "OMIT temperature/top_p when the model's catalog doesn't list them (Fable 5 shape)" in {
      val fable = Model.id("anthropic/claude-fable-5-test")
      // Fable's real supported_parameters: includes tools/tool_choice, OMITS
      // temperature and top_p.
      register(fable, Set("tools", "tool_choice", "max_tokens", "reasoning", "structured_outputs"))
      val body = renderedBody(fable)
      body.get("temperature") shouldBe None
      body.get("top_p") shouldBe None
    }

    "SEND temperature/top_p when the model's catalog lists them" in {
      val capable = Model.id("anthropic/claude-capable-test")
      register(capable, Set("temperature", "top_p", "tools", "tool_choice", "max_tokens"))
      val body = renderedBody(capable)
      body.get("temperature").map(_.asDouble) shouldBe Some(0.7)
      body.get("top_p").map(_.asDouble) shouldBe Some(0.9)
    }

    "FAIL-OPEN (send them) when the catalog is empty — cold-cache, where the self-heal backstops" in {
      val cold = Model.id("anthropic/claude-cold-test")
      register(cold, Set.empty)
      val body = renderedBody(cold)
      body.get("temperature").map(_.asDouble) shouldBe Some(0.7)
    }
  }

  "tear down" should {
    "dispose TestSigil" in { TestSigil.shutdown.sync(); succeed }
  }
}
