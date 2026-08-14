package spec

import fabric.*
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, ContextFrame, ParticipantProjection, RecentToolInvocation, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderMessage, ProviderRequest}
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.google.GoogleProvider
import sigil.provider.openai.OpenAIProvider
import sigil.provider.wire.OpenAIChatCompletions
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import sigil.tool.ToolRoster

/**
 * Prompt caches (Anthropic `cache_control`, Gemini implicit + explicit
 * `cachedContents`, OpenAI-compatible automatic prefix caches) key on a
 * byte-stable request PREFIX: tools, then system prompt, then message
 * history, in order. The per-turn volatile context (Recently used tools /
 * Suggested tools / Repeated calls / Memories / greeting hint) therefore
 * must ride BEHIND every cacheable span — a volatile segment anywhere in
 * the system prompt, even without its own cache marker, sits upstream of
 * the entire message history and invalidates the history's cache prefix
 * on every turn (observed live: ~230K tokens re-written to cache per
 * turn against ~32K read, on byte-identical histories).
 *
 * These specs pin the placement: the volatile segment renders as a
 * trailing message (`[system]`-marked user message on Anthropic /
 * Google, system-role tail on OpenAI-compatible dialects), the system
 * prompt carries only the stable text, and the full prefix — tools,
 * system, shared history including cache markers — is byte-identical
 * across consecutive turns whose volatile sources differ. OpenAI
 * Responses is the deliberate exception: its `instructions` channel is
 * per-request (outside the server-held transcript), so the volatile
 * segment stays there.
 */
class VolatileContextTailPlacementSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("volatile-tail-conv")
  private val anthropicModelId = Model.id("anthropic", "claude-haiku-4-5")

  private val baseFrames = Vector(
    ContextFrame.Text("first user message", TestUser, Id[Event]("seed-1")),
    ContextFrame.Text("agent reply", TestAgent, Id[Event]("seed-2")),
    ContextFrame.Text("latest user message", TestUser, Id[Event]("seed-3"))
  )

  private val grownFrames = baseFrames ++ Vector(
    ContextFrame.Text("second agent reply", TestAgent, Id[Event]("seed-4")),
    ContextFrame.Text("follow-up user message", TestUser, Id[Event]("seed-5"))
  )

  /** TurnInput seeded with a participant projection carrying volatile
    * sources. The values themselves are arbitrary — the tests only care
    * where they land in the rendered request. */
  private def turnWith(suggested: List[String], recent: List[String],
                       frames: Vector[ContextFrame] = baseFrames): TurnInput = {
    val proj = ParticipantProjection.empty(TestAgent, convId)
      .copy(
        suggestedTools = suggested.map(s => ToolName.parse(s).fold(sys.error, identity)),
        recentToolInvocations = recent.zipWithIndex.map { case (n, i) =>
          RecentToolInvocation(
            toolName    = ToolName.parse(n).fold(sys.error, identity),
            argsHash    = s"h-$n-$i",
            argsPreview = s"""{"x":$i}""",
            invokedAt   = Timestamp(System.currentTimeMillis() - 1_000L * (i + 1))
          )
        }
      )
    TurnInput(
      conversationId         = convId,
      frames                 = frames,
      participantProjections = Map(TestAgent -> proj)
    )
  }

  private def requestFor(t: TurnInput, modelId: Id[Model] = anthropicModelId): ProviderRequest =
    ConversationRequest(
      conversationId     = convId,
      model              = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = t,
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      tools              = CoreTools.all,
      chain              = List(TestUser, TestAgent)
    )

  private def renderBody(provider: sigil.provider.Provider, t: TurnInput,
                         modelId: Id[Model] = anthropicModelId): Json = {
    val httpReq = provider.requestConverter(requestFor(t, modelId)).sync()
    httpReq.content match {
      case Some(c: spice.http.content.StringContent) => fabric.io.JsonParser(c.value)
      case _                                          => obj()
    }
  }

  private val volatileTurnA = turnWith(
    suggested = List("dispatch_workers", "grep"),
    recent    = List("find_capability")
  )
  private val volatileTurnB = turnWith(
    suggested = List("lsp_find_references", "bsp_compile"),   // DIFFERENT
    recent    = List("respond_options", "find_capability"),   // DIFFERENT
    frames    = grownFrames                                   // conversation advanced
  )

  "AnthropicProvider volatile-context placement" should {
    val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)

    "render the system prompt as a single cached block with no volatile content" in {
      val body = renderBody(provider, volatileTurnA)
      val systemArr = body("system").asVector
      systemArr should have size 1
      systemArr.head("cache_control") shouldBe obj("type" -> str("ephemeral"), "ttl" -> str("1h"))
      val stableText = systemArr.head("text").asString
      stableText should not include "== Suggested tools =="
      stableText should not include "== Recently used tools =="
    }

    "carry the volatile segment as the final message, unmarked" in {
      val body = renderBody(provider, volatileTurnA)
      val messages = body("messages").asVector
      messages.size shouldBe baseFrames.size + 1
      val tail = messages.last
      tail("role").asString shouldBe "user"
      val tailText = tail("content").asVector.map(_("text").asString).mkString
      tailText should startWith("[system] ")
      tailText should (include ("Suggested tools") or include ("Recently used tools"))
      tail("content").asVector.foreach(_.get("cache_control") shouldBe None)
    }

    "keep the full prefix byte-identical across turns whose volatile sources differ" in {
      val bodyA = renderBody(provider, volatileTurnA)
      val bodyB = renderBody(provider, volatileTurnB)
      // Tools and system — the head of the prefix — must not shift.
      bodyA("tools") shouldBe bodyB("tools")
      bodyA("system") shouldBe bodyB("system")
      // The shared history — every message the two turns have in common,
      // INCLUDING cache_control placement — must be byte-identical. This
      // is the property the prompt cache keys on; the volatile churn and
      // the new turn's messages may only extend the sequence, never
      // rewrite it.
      val msgsA = bodyA("messages").asVector.dropRight(1) // drop volatile tail
      val msgsB = bodyB("messages").asVector
      msgsA.zipWithIndex.foreach { case (m, i) =>
        withClue(s"message[$i] diverged between consecutive turns: ") {
          msgsB(i) shouldBe m
        }
      }
      // Sanity — the volatile tails themselves DO differ.
      bodyA("messages").asVector.last should not equal bodyB("messages").asVector.last
    }

    "carry only the topic block in the tail when no other volatile source exists" in {
      val bare = TurnInput(conversationId = convId, frames = baseFrames)
      val body = renderBody(provider, bare)
      val messages = body("messages").asVector
      messages.size shouldBe baseFrames.size + 1
      val tailText = messages.last("content").asVector.map(_("text").asString).mkString
      tailText should include("Current topic:")
      tailText should not include "== Suggested tools =="
      tailText should not include "== Recently used tools =="
    }
  }

  "GoogleProvider volatile-context placement" should {
    val provider = GoogleProvider(apiKey = "test-key", sigilRef = TestSigil, contextCaching = false)
    val googleModelId = Model.id("google", "gemini-2.5-flash")

    "keep systemInstruction stable and carry the volatile segment as the final content entry" in {
      val body = renderBody(provider, volatileTurnA, googleModelId)
      val sysText = body("systemInstruction")("parts").asVector.head("text").asString
      sysText should not include "== Suggested tools =="
      sysText should not include "== Recently used tools =="
      val contents = body("contents").asVector
      val tail = contents.last
      tail("role").asString shouldBe "user"
      val tailText = tail("parts").asVector.map(_("text").asString).mkString
      tailText should startWith("[system] ")
      tailText should (include ("Suggested tools") or include ("Recently used tools"))
    }
  }

  "OpenAIChatCompletions default preprocess" should {
    "hand renderers the stable system and the volatile-tailed messages" in {
      val call = sigil.provider.ProviderCall(
        model              = TestSigil.testModel(anthropicModelId),
        system             = "stable system prompt",
        messages           = Vector(ProviderMessage.User("hello")),
        roster = ToolRoster(Vector.empty),
        builtInTools       = Set.empty,
        toolChoice         = sigil.provider.ToolChoice.Auto,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
        systemVolatile     = "\n== Suggested tools ==\n- grep\n"
      )
      val pre = OpenAIChatCompletions.Config(providerNamespace = "test", providerName = "Test").preprocess(call)
      pre.systemPrompt shouldBe "stable system prompt"
      pre.messages.last shouldBe ProviderMessage.System("\n== Suggested tools ==\n- grep\n")
    }
  }

  "OpenAIProvider (Responses)" should {
    val provider = OpenAIProvider(apiKey = "sk-test", sigilRef = TestSigil)
    val openAiModelId = Model.id("openai", "gpt-5.2")

    "keep the volatile segment in per-request instructions, not the transcript" in {
      val body = renderBody(provider, volatileTurnA, openAiModelId)
      val instructions = body("instructions").asString
      instructions should (include ("Suggested tools") or include ("Recently used tools"))
      // No volatile tail item in the input transcript — `instructions`
      // is per-request, while input items accumulate server-side across
      // `previous_response_id` chains and the incremental path's tail
      // filter keeps only User items.
      val inputTexts = body("input").asVector.map(i => fabric.io.JsonFormatter.Compact(i))
      inputTexts.foreach(_ should not include "Suggested tools")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
