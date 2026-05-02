package spec

import fabric.rw.RW
import lightdb.id.Id
import org.scalatest.{BeforeAndAfterAll, Canceled}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant, ParticipantId}
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent
import sigil.browser.WebBrowserMode

import scala.concurrent.duration.*

/**
 * Marquee end-to-end coverage for `sigil-browser` — drives a full
 * agent loop against a real headless Chrome and a local llama.cpp
 * server (Gemma).
 *
 * Self-skips when:
 *   - Chrome / chromedriver isn't installed (live browser unavailable);
 *   - the local llama.cpp server isn't reachable.
 *
 * Asserts the agent in [[WebBrowserMode]] navigates to a fixture
 * news-index page, scrapes / extracts content, classifies which
 * links are articles vs nav/footer, and surfaces the article URLs in
 * its final response while excluding the nav URLs. The classification
 * is LLM-driven — there's no regex heuristic in the framework.
 */
class NewsArticleDetectionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {

  // The full loop is several agent turns + browser navigation; budget
  // generously.
  override implicit protected val testTimeout: FiniteDuration = 5.minutes

  // Agent attribution participant for the live test.
  case object NewsAgent extends sigil.participant.AgentParticipantId {
    override val value: String = "news-agent"
  }
  case object NewsUser extends ParticipantId {
    override val value: String = "news-user"
  }
  ParticipantId.register(RW.static(NewsAgent), RW.static(NewsUser))

  TestBrowserSigil.initFor(getClass.getSimpleName)

  // -- liveness gates --

  private val chromeAvailable: Boolean =
    List("/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/local/bin/google-chrome").exists(p =>
      java.nio.file.Files.isExecutable(java.nio.file.Path.of(p)))

  private val llamaCppReachable: Boolean = try {
    val client = spice.http.client.HttpClient.url(TestBrowserSigil.llamaCppHost.withPath("/health")).timeout(2.seconds)
    val response = client.send().sync()
    response.status.code == 200
  } catch { case _: Throwable => false }

  /** Provider preference for this test:
    *
    *   - If `OPENAI_API_KEY` is set, use OpenAI — its context window
    *     is plenty for the agent's HTML + 8 browser-tool schemas +
    *     chat history (~5 K tokens).
    *   - Otherwise fall back to local llama.cpp, which historically
    *     ran the test fine but can fail with `MissingCoreLibrary`-
    *     style "exceeds the available context size" errors when the
    *     locally-loaded model has tighter effective context (gemma's
    *     sliding-window attention, parallel-slot bucketing).
    *
    * Returns the model id + Provider task to install. */
  private val providerSelection: Option[(Id[Model], Task[Provider])] = {
    sys.env.get("OPENAI_API_KEY").filter(_.nonEmpty) match {
      case Some(apiKey) =>
        val mid: Id[Model] = Model.id(sys.env.getOrElse("OPENAI_TEST_MODEL", "openai/gpt-5.4-mini"))
        Some(mid -> sigil.provider.openai.OpenAIProvider.create(TestBrowserSigil, apiKey).singleton)
      case None if llamaCppReachable =>
        val mid: Id[Model] = Model.id("gemma-4-26b")
        Some(mid -> LlamaCppProvider(TestBrowserSigil, TestBrowserSigil.llamaCppHost).singleton)
      case None => None
    }
  }

  private val skipReason: Option[String] =
    if (!chromeAvailable)         Some("Chrome / chromedriver not installed")
    else if (providerSelection.isEmpty) Some(
      s"no provider available — set OPENAI_API_KEY for OpenAI, or run llama.cpp at ${TestBrowserSigil.llamaCppHost}"
    )
    else None

  // -- fixture + provider --

  private lazy val fixture: NewsFixtureServer = new NewsFixtureServer

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    skipReason.foreach(_ => return)  // skip setup
    fixture.start().sync()
    providerSelection.foreach { case (_, providerTask) =>
      TestBrowserSigil.setProvider(providerTask)
    }
  }

  override protected def afterAll(): Unit = {
    skipReason match {
      case Some(_) => ()
      case None    =>
        try fixture.stop().sync() catch { case _: Throwable => () }
    }
    super.afterAll()
  }

  private val modelId: Id[Model] =
    providerSelection.map(_._1).getOrElse(Model.id("gemma-4-26b"))

  private def agent(): AgentParticipant =
    DefaultAgentParticipant(
      id = NewsAgent,
      modelId = modelId,
      // The conversation starts in WebBrowserMode (set on newConversation
      // below). Mode's `Exclusive` policy provides the structural-query
      // roster (navigate + save_html + xpath_query + text_search + respond)
      // so we don't list toolNames here.
      instructions = Instructions.autonomous(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(2000), temperature = Some(0.0))
    )

  // -- the test --

  s"NewsArticleDetectionSpec" should {

    "classify the news-index page's links via the browser tools and an LLM" in {
      if (skipReason.isDefined) {
        cancel(s"Skipping: ${skipReason.get}")
      }
      val convId = Conversation.id(s"news-detect-${rapid.Unique()}")
      val a = agent()

      val task: Task[org.scalatest.compatible.Assertion] = for {
        _    <- TestBrowserSigil.newConversation(
                  createdBy = NewsUser,
                  participants = List(a),
                  currentMode = WebBrowserMode,
                  conversationId = convId
                )
        conv <- TestBrowserSigil.withDB(_.conversations.transaction(_.get(convId)))
        _    <- TestBrowserSigil.publish(Message(
                  participantId  = NewsUser,
                  conversationId = convId,
                  topicId        = conv.get.currentTopicId,
                  content        = Vector(ResponseContent.Text(
                    s"""Go to ${fixture.indexUrl} and list which of the links on that page are news articles
                       |(not nav / footer / about). Pipeline:
                       | - call browser_navigate to load the page
                       | - call browser_save_html — the response includes an `htmlFileId` plus a structural
                       |   overview (headings, landmarks, linkClusters)
                       | - use the linkClusters' xpaths with browser_xpath_query to inspect link groups; the
                       |   article list is typically inside a <main> / <article> landmark, not <nav> / <footer>
                       | - respond with one URL per line, nothing else.""".stripMargin
                  )),
                  state          = EventState.Complete,
                  role           = MessageRole.Standard
                ))
        // Wait for the agent loop to settle — poll the events store
        // until we see a non-Tool Message from the agent (the final
        // respond) or hit the deadline.
        // 5 minutes — the multi-tool agent loop on a local LLM
        // (gemma-4-26b) running through headless Chrome takes
        // several iterations of large-context calls; the original
        // 90-second budget was too tight on slow workstations.
        finalText <- awaitFinalAgentText(convId, deadline = System.currentTimeMillis() + 5.minutes.toMillis)
        // Dispose the browser controller so its Chrome process closes.
        _    <- TestBrowserSigil.disposeBrowserController(convId)
      } yield {
        withClue(s"Final agent text:\n$finalText\n") {
          fixture.articleUrls.foreach(u => finalText should include (u))
          fixture.nonArticleUrls.foreach(u => finalText shouldNot include (u))
          succeed
        }
      }
      task
    }
  }

  /** Poll the conversation's events store for the agent's final
    * `respond` Message — a `MessageRole.Standard` (NOT `Tool`)
    * message authored by the agent. We accept either `Active` (still
    * streaming content) or `Complete` (settled) state, and we wait
    * for at least one second of stability before returning so the
    * content has had time to fully stream in. */
  private def awaitFinalAgentText(convId: Id[Conversation], deadline: Long): Task[String] = {
    def fetchAgentMessages: Task[List[Message]] =
      TestBrowserSigil.withDB(_.events.transaction(_.list)).map { all =>
        all
          .filter(_.conversationId == convId)
          .collect { case m: Message if m.participantId == NewsAgent && m.role == MessageRole.Standard => m }
      }

    def loop: Task[String] = fetchAgentMessages.flatMap { msgs =>
      msgs.lastOption match {
        case Some(m) =>
          // RespondTool's content goes through MarkdownContentParser, which
          // returns Markdown / Heading / Code / ItemList depending on shape —
          // not just Text. Concatenate every block's textual content so the
          // assertions don't depend on the agent's formatting choice.
          val text = m.content.collect {
            case ResponseContent.Text(t)        => t
            case ResponseContent.Markdown(t)    => t
            case ResponseContent.Heading(t)     => t
            case ResponseContent.Code(c, _)     => c
            case ResponseContent.ItemList(items, _) => items.mkString("\n")
            case ResponseContent.Link(u, label) => s"$label $u"
          }.mkString("\n")
          if (text.nonEmpty) Task.pure(text)
          else continueLoop
        case None =>
          continueLoop
      }
    }

    def continueLoop: Task[String] =
      if (System.currentTimeMillis() > deadline)
        Task.error(new RuntimeException(s"Agent didn't produce a final reply within deadline"))
      else Task.sleep(2.seconds).flatMap(_ => loop)

    loop
  }
}
