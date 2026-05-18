package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  MessageContent, Provider, ProviderCall, ProviderEvent, ProviderMessage, ProviderType
}
import sigil.tool.core.RespondTool
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for sigil bug #59 — `Provider.emergencyShed`'s
 * message-trim no longer runs `estimateOf` per dropped message.
 * For HTTP-backed estimators (LlamaCpp's `/apply-template +
 * /tokenize`), the original code was O(n²) in messages × HTTP
 * round-trips; the bulk-drop replacement gets to a fitting
 * residual in 2-3 estimateOf calls regardless of how many
 * messages have to drop.
 */
class EmergencyShedBulkDropSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Provider stub whose `estimateRequest` returns the count of
   * 'x' characters across all message contents — so a message
   * containing "xxxxx" costs 5 tokens. Counts how many times
   * estimateOf was called so the spec can prove the new bulk
   * path makes a small, bounded number of calls.
   */
  private class CountingProvider(model: Model) extends Provider {
    val estimateCalls: AtomicInteger = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = List(model)
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override protected def estimateRequest(call: ProviderCall): Int = {
      estimateCalls.incrementAndGet()
      val tokens = call.messages.foldLeft(0) { (acc, m) =>
        acc +
          (m match {
            case ProviderMessage.User(blocks) =>
              blocks.iterator.collect { case t: MessageContent.Text => t.text.count(_ == 'x') }.sum
            case ProviderMessage.Assistant(c, _) => c.count(_ == 'x')
            case ProviderMessage.ToolResult(_, c) => c.count(_ == 'x')
            case ProviderMessage.System(c) => c.count(_ == 'x')
            case _ => 0
          })
      }
      tokens
    }

    /**
     * Public estimate (since the trait's is `protected`).
     */
    def publicEstimate(call: ProviderCall): Int = estimateRequest(call)

    /**
     * Test-only — exposes the private `emergencyShed` via
     * reflection so the spec can drive it directly + assert
     * estimateOf call count.
     */
    def runShed(initial: ProviderCall, limit: Int): ProviderCall = {
      val mEmerg = classOf[Provider].getDeclaredMethod(
        "emergencyShed",
        classOf[ProviderCall],
        classOf[Int],
        classOf[_root_.sigil.tokenize.Tokenizer],
        classOf[Function1[?, ?]]
      )
      mEmerg.setAccessible(true)
      val estimateFn: ProviderCall => Int = c => publicEstimate(c)
      mEmerg.invoke(this, initial, Int.box(limit), tokenizer, estimateFn).asInstanceOf[ProviderCall]
    }
  }

  private val testModel: Model = Model(
    canonicalSlug = "test/model",
    huggingFaceId = "",
    name = "Test",
    description = "",
    contextLength = 1000L,
    architecture = sigil.db.ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = sigil.db.ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = sigil.db.ModelTopProvider(contextLength = Some(1000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = sigil.db.ModelLinks(details = ""),
    created = lightdb.time.Timestamp(),
    _id = Model.id("test", "shed-model")
  )

  "Provider.emergencyShed" should {

    "drop messages in O(1) estimateOf calls regardless of how many need shedding" in {
      // 1000 messages × 5 tokens each = 5000-token total. Limit at
      // 100 tokens — need to drop ~980 messages.
      val msgs = (1 to 1000).map(_ => ProviderMessage.User("xxxxx")).toVector
      val call = ProviderCall(
        modelId = testModel._id,
        system = "",
        messages = msgs,
        tools = Vector.empty,
        builtInTools = Set.empty,
        toolChoice = sigil.provider.ToolChoice.None,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        currentMode = ConversationMode
      )
      val provider = new CountingProvider(testModel)
      val before = provider.estimateCalls.get()
      val shed = provider.runShed(call, limit = 100)
      val callsMade = provider.estimateCalls.get() - before

      Task {
        // Critical regression bound: pre-fix, the O(n) loop would
        // make ~980 calls. Post-fix, the bulk path makes 2-3 calls
        // total (initial check + post-drop confirm + maybe one
        // convergence iter).
        callsMade should be <= 5
        // And the result actually fits.
        shed.messages.size should be < msgs.size
        provider.publicEstimate(shed) should be <= 100
      }
    }

    "leave a fitting call alone (no-op short-circuit)" in {
      val call = ProviderCall(
        modelId = testModel._id,
        system = "",
        messages = Vector(ProviderMessage.User("xxxxx")), // 5 tokens
        tools = Vector.empty,
        builtInTools = Set.empty,
        toolChoice = sigil.provider.ToolChoice.None,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        currentMode = ConversationMode
      )
      val provider = new CountingProvider(testModel)
      val shed = provider.runShed(call, limit = 100)
      Task {
        shed.messages.size shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
