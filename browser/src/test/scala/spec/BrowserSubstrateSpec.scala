package spec

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.browser.{BrowserState, BrowserStateDelta, WebBrowserMode}
import sigil.conversation.{Conversation, Topic}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.signal.{EventState, Signal}
import sigil.storage.StoredFile
import fabric.rw.RW

/**
 * Coverage for the phase-1a browser substrate that doesn't need a
 * live Chrome:
 *
 *   - [[WebBrowserMode]] — name, skill, tool policy, built-ins
 *   - [[BrowserState]] / [[BrowserStateDelta]] — registration and
 *     polymorphic round-trip via the framework's `Signal` discriminator
 *   - `BrowserStateDelta.apply` semantics — partial-update merge
 */
class BrowserSubstrateSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  // Register the spec's participant before initFor — so it's in the
  // polymorphic ParticipantId discriminator before BrowserState round-
  // tripping reaches into it.
  case object SpecUser extends ParticipantId {
    override val value: String = "spec-user"
  }
  ParticipantId.register(RW.static(SpecUser))

  TestBrowserSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id("browser-substrate-spec")
  private val topicId: Id[Topic] = Id("topic-browser-substrate")

  "WebBrowserMode" should {
    "carry the right name, skill, and tool roster" in {
      WebBrowserMode.name shouldBe "web-browser"
      WebBrowserMode.skill shouldBe defined
      WebBrowserMode.skill.get.name shouldBe "web-browser"
      WebBrowserMode.skill.get.content should include ("browser_navigate")

      val toolNames = WebBrowserMode.tools match {
        case sigil.provider.ToolPolicy.Exclusive(names) => names.map(_.value).toSet
        case _ => fail("WebBrowserMode.tools must be ToolPolicy.Exclusive")
      }
      toolNames should contain allOf (
        "browser_navigate", "browser_screenshot", "browser_save_html",
        "browser_xpath_query", "browser_text_search",
        "browser_click", "browser_type", "browser_scroll"
      )
      WebBrowserMode.builtInTools shouldBe Set.empty
    }
  }

  "BrowserState" should {

    "round-trip through the polymorphic Signal RW (registered by BrowserSigil)" in {
      rapid.Task {
        val htmlId: Id[StoredFile] = Id("stored-html-1")
        val original = BrowserState(
          participantId = SpecUser,
          conversationId = convId,
          topicId = topicId,
          timestamp = Timestamp(1700_000_000_000L),
          url = Some("https://example.com/"),
          title = Some("Example"),
          loading = false,
          htmlFileId = Some(htmlId),
          state = EventState.Complete
        )
        val signalRW = summon[RW[Signal]]
        val json = signalRW.read(original.asInstanceOf[Signal])
        val back = signalRW.write(json)
        back shouldBe a [BrowserState]
        val recovered = back.asInstanceOf[BrowserState]
        recovered.url shouldBe Some("https://example.com/")
        recovered.title shouldBe Some("Example")
        recovered.htmlFileId shouldBe Some(htmlId)
        recovered.state shouldBe EventState.Complete
      }
    }

    "be discoverable in the framework's eventSubtypeNames" in {
      rapid.Task {
        TestBrowserSigil.eventSubtypeNames should contain ("BrowserState")
      }
    }
  }

  "BrowserStateDelta" should {

    "merge only the supplied fields onto a target BrowserState" in {
      rapid.Task {
        val target = BrowserState(
          participantId = SpecUser,
          conversationId = convId,
          topicId = topicId,
          url = Some("https://old.example/"),
          title = Some("Old"),
          loading = true
        )
        val delta = BrowserStateDelta(
          target = target._id,
          conversationId = convId,
          url = Some("https://new.example/"),
          loading = Some(false)
        )
        val updated = delta.apply(target).asInstanceOf[BrowserState]
        updated.url shouldBe Some("https://new.example/")
        updated.title shouldBe Some("Old")  // preserved (Option.orElse keeps Some)
        updated.loading shouldBe false
      }
    }

    "be discoverable in the framework's deltaSubtypeNames" in {
      rapid.Task {
        TestBrowserSigil.deltaSubtypeNames should contain ("BrowserStateDelta")
      }
    }
  }

  "tear down" should {
    "dispose TestBrowserSigil" in TestBrowserSigil.shutdown.map(_ => succeed)
  }
}
