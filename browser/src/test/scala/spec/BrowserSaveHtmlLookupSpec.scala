package spec

import fabric.io.JsonParser
import fabric.rw.RW
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.information.{Information, StoredInformation}
import sigil.participant.ParticipantId
import sigil.tool.{TextToolOutput, ToolContext, ToolResult}
import sigil.browser.tool.{BrowserSaveHtmlInput, BrowserSaveHtmlTool}

/**
 * Sigil #377 — a page captured by `browser_save_html` must be readable
 * WHOLE the same way an uploaded HTML file is: the tool registers a
 * lookup-able [[Information]] (not just a raw `StoredFile`), and its
 * result carries the `informationId`. Drives the real tool against a
 * live headless Chrome, then reads the page back through
 * [[sigil.Sigil.getInformation]] — the exact resolution path the
 * `lookup` tool uses.
 *
 * Self-skips when Chrome/Chromium isn't installed.
 */
class BrowserSaveHtmlLookupSpec extends AnyWordSpec with Matchers {

  case object SpecUser extends ParticipantId {
    override val value: String = "spec-user"
  }
  ParticipantId.register(RW.static(SpecUser))

  TestBrowserSigil.initFor(getClass.getSimpleName)

  private val chromeAvailable: Boolean =
    List("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
         "/usr/bin/chromium", "/usr/local/bin/google-chrome")
      .exists(p => new java.io.File(p).canExecute)

  private val modelId: Id[Model] = Model.id("test", "model")
  private val model: Model = Model(
    canonicalSlug = modelId.value, huggingFaceId = "", name = modelId.value,
    description = "test fixture", contextLength = 131072L,
    architecture = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
    pricing = ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
    topProvider = ModelTopProvider(Some(131072L), Some(16000L), isModerated = false),
    perRequestLimits = None, supportedParameters = Set("max_tokens"),
    knowledgeCutoff = None, expirationDate = None, links = ModelLinks(""),
    created = Timestamp(), _id = modelId
  )

  private val marker = "SIGIL-377-WHOLE-PAGE-MARKER"

  /** Local fixture server for the marker page. A `data:text/html` URL
    * was used before, but CI's Chrome build silently commits an EMPTY
    * document for top-level `data:` navigations (the capture came back
    * `<html><head></head><body></body></html>`) while local builds
    * render it — the test's subject is the Information registration,
    * not data-URL policy, so serve the page over plain HTTP like the
    * module's other browser fixtures. */
  private def markerServer(): spice.http.server.MutableHttpServer = {
    val s = new spice.http.server.MutableHttpServer
    s.config.clearListeners().addListeners(spice.http.server.config.HttpServerListener(port = None))
    s.handler.handle { exchange =>
      exchange.modify { response =>
        rapid.Task(response.withContent(spice.http.content.Content.string(
          s"<html><body><h1>Heading</h1><p>$marker</p></body></html>",
          spice.net.ContentType.`text/html`
        )))
      }
    }
    s
  }

  "browser_save_html (sigil #377)" should {
    "register a lookup-able Information so the whole captured page is retrievable" in {
      if (!chromeAvailable) cancel("Chrome/Chromium not installed — live browser test")

      val convId = Conversation.id("save-html-lookup")
      val conv = Conversation(topics = List(TopicEntry(TestTopicId, "test", "test")), _id = convId)
      TestBrowserSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()

      val server = markerServer()
      server.start().sync()
      // Explicit IPv4 — spice's listener binds 127.0.0.1 only, and CI's
      // Chrome resolves `localhost` to ::1 first, getting connection
      // refused and committing an empty error-page document.
      val page = s"http://127.0.0.1:${server.config.listeners().head.port.getOrElse(0)}/"

      val controller = TestBrowserSigil.browserController(convId, SpecUser, List(SpecUser)).sync()
      try {
        controller.run(_.navigate(page)).sync()
        val turn = TurnContext(
          sigil        = TestBrowserSigil,
          chain        = List(SpecUser),
          conversation = conv,
          turnInput    = TurnInput(ConversationView(conversationId = convId)),
          model        = model
        )
        val tool = new BrowserSaveHtmlTool
        val ctx  = ToolContext(turn, Event.id(), tool.name)

        val result = tool.executeResult(BrowserSaveHtmlInput(), ctx).sync()
        val payload = result match {
          case ToolResult.Success(TextToolOutput(text)) => JsonParser(text)
          case other => fail(s"expected Success(TextToolOutput), got $other")
        }

        // The result surfaces the lookup handle...
        val informationId = payload.get("informationId").map(_.asString)
          .getOrElse(fail(s"result carried no informationId: $payload"))

        // ...and that handle resolves to the whole page through the same
        // path the `lookup` tool uses.
        val info: Option[Information] = TestBrowserSigil.getInformation(Id(informationId)).sync()
        info shouldBe defined
        val content = info.get match {
          case s: StoredInformation => s.content
          case other                => fail(s"expected a StoredInformation, got $other")
        }
        content should include(marker)
      } finally {
        TestBrowserSigil.disposeBrowserController(convId).sync()
        try server.stop().sync() catch { case _: Throwable => () }
      }
    }
  }

  "tear down" should {
    "dispose TestBrowserSigil" in {
      TestBrowserSigil.shutdown.sync()
      succeed
    }
  }
}
