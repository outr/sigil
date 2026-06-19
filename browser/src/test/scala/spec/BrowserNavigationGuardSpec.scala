package spec

import fabric.rw.RW
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tool.{ToolContext, ToolResult}
import sigil.browser.tool.{BrowserNavigateInput, BrowserNavigateTool}
import spice.net.url

/**
 * Sigil #403 — `BrowserNavigateTool` consults [[sigil.browser.BrowserSigil.navigationGuard]]
 * before navigating, so a host can blacklist URLs (e.g. its own storefront,
 * which loads the live not the draft theme) instead of removing the tool
 * wholesale. A vetoed navigation short-circuits to a recoverable failure
 * BEFORE the browser is even resolved — so this needs no live Chrome.
 */
class BrowserNavigationGuardSpec extends AnyWordSpec with Matchers {
  case object SpecUser extends ParticipantId {
    override def value: String = "nav-guard-user"
  }
  ParticipantId.register(RW.static(SpecUser))

  TestBrowserSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  private val model: Model = Model(
    canonicalSlug = modelId.value, huggingFaceId = "", name = modelId.value,
    description = "test", contextLength = 8192L,
    architecture = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(8192L), maxCompletionTokens = Some(2048L), isModerated = false),
    perRequestLimits = None, supportedParameters = Set("max_tokens"),
    knowledgeCutoff = None, expirationDate = None, links = ModelLinks(""),
    created = Timestamp(), _id = modelId
  )

  private val convId = Conversation.id("nav-guard-conv")
  private val conv = Conversation(topics = List(TopicEntry(Id("t"), "test", "test")), _id = convId)
  TestBrowserSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()

  private def navigate(url: String): ToolResult[?] = {
    val turn = TurnContext(
      sigil = TestBrowserSigil, chain = List(SpecUser), conversation = conv,
      turnInput = TurnInput(ConversationView(conversationId = convId)), model = model)
    val tool = new BrowserNavigateTool
    tool.executeResult(BrowserNavigateInput(url = url), ToolContext(turn, Event.id(), tool.name)).sync()
  }

  "BrowserNavigateTool navigation guard (#403)" should {

    "block a vetoed URL with the guard's reason — without resolving the browser" in {
      TestBrowserSigil.setNavigationGuard { (url, _, _) =>
        Task.pure(Option.when(url.host.endsWith("mystore.myshopify.com"))(
          "Don't load your own storefront in the browser — use preview_theme."))
      }
      try {
        navigate("https://mystore.myshopify.com/products/widget") match {
          case ToolResult.Failure(message, _, _) =>
            message should include("preview_theme")
          case other => fail(s"expected a Failure from the guard, got: $other")
        }
      } finally TestBrowserSigil.resetNavigationGuard()
    }

    "allow a non-vetoed host (guard returns None)" in {
      TestBrowserSigil.setNavigationGuard { (url, _, _) =>
        Task.pure(Option.when(url.host.endsWith("mystore.myshopify.com"))("blocked"))
      }
      try {
        // The guard itself passes a third-party host — proving the veto is
        // host-scoped, not blanket. (We don't drive the actual navigate here,
        // which would need a live Chrome.)
        TestBrowserSigil.navigationGuard(url"https://reference.example.com/", List(SpecUser), convId).sync() shouldBe None
      } finally TestBrowserSigil.resetNavigationGuard()
    }

    "default to allowing everything (no guard configured)" in {
      TestBrowserSigil.resetNavigationGuard()
      TestBrowserSigil.navigationGuard(url"https://anything.example/", List(SpecUser), convId).sync() shouldBe None
    }
  }
}
