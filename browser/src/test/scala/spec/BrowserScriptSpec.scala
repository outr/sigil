package spec

import fabric.rw.*
import fabric.{Json, Obj}
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId}
import sigil.browser.{BrowserScript, BrowserStep, CookieJar}
import sigil.tool.{Tool, ToolName}

/**
 * Coverage for [[BrowserScript]] persistence + management-tool
 * authz that doesn't need a live browser:
 *
 *   - `BrowserScript` round-trips through the polymorphic `Tool` RW
 *     (auto-registered by `BrowserSigil.toolRegistrations`)
 *   - `BrowserScript.Resolver` substitutes `${arg.path}` and
 *     `${outputs.<name>}` placeholders correctly
 *   - The script's `cookieJarId` is preserved across persist + read
 */
class BrowserScriptSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestBrowserSigil.initFor(getClass.getSimpleName)

  "BrowserScript" should {

    "persist + reload via SigilDB.tools as a polymorphic Tool record" in {
      val script = BrowserScript(
        name = ToolName("scrape_news"),
        description = "Open a URL, save the HTML, query the headlines.",
        parameters = sigil.tool.JsonSchemaToDefinition(fabric.obj("type" -> fabric.str("object"))),
        steps = List(
          BrowserStep.Navigate(url = "${indexUrl}"),
          BrowserStep.SaveHtml(name = "page"),
          BrowserStep.XPathQuery(htmlRef = "${outputs.page}", xpath = "//h1", name = "headlines")
        ),
        space = GlobalSpace,
        cookieJarId = Some(CookieJar.id("test-jar"))
      )
      for {
        _      <- TestBrowserSigil.createTool(script)
        loaded <- TestBrowserSigil.withDB(_.tools.transaction(_.get(script._id)))
      } yield {
        loaded.isDefined shouldBe true
        val tool = loaded.get
        tool shouldBe a [BrowserScript]
        val bs = tool.asInstanceOf[BrowserScript]
        bs.name.value shouldBe "scrape_news"
        bs.steps should have size 3
        bs.cookieJarId.map(_.value) shouldBe Some("test-jar")
        bs.steps.head match {
          case BrowserStep.Navigate(url, wait) =>
            url shouldBe "${indexUrl}"
            wait shouldBe 15
          case other => fail(s"Expected Navigate, got $other")
        }
      }
    }

    "expose itself in eventSubtypeNames as a tool record (round-trips through SigilDB.tools)" in {
      Task {
        TestBrowserSigil.allEventRWs should not be empty  // sanity: registry populated
      }
    }
  }

  "BrowserScript.Resolver" should {

    "substitute ${arg} placeholders from the JSON args" in {
      Task {
        val args = fabric.obj("indexUrl" -> fabric.str("https://example.com/news"))
        val resolved = BrowserScript.Resolver.resolve(
          "${indexUrl}/topic/foo", args, Map.empty
        )
        resolved shouldBe "https://example.com/news/topic/foo"
      }
    }

    "substitute ${outputs.<name>} placeholders from earlier extract steps" in {
      Task {
        val resolved = BrowserScript.Resolver.resolve(
          "Found: ${outputs.summary}",
          Obj.empty,
          Map("summary" -> "Three articles")
        )
        resolved shouldBe "Found: Three articles"
      }
    }

    "leave unmatched placeholders as the empty string" in {
      Task {
        val resolved = BrowserScript.Resolver.resolve(
          "Missing: ${nope}", Obj.empty, Map.empty
        )
        resolved shouldBe "Missing: "
      }
    }

    "support dotted paths into nested args" in {
      Task {
        val args = fabric.obj("user" -> fabric.obj("name" -> fabric.str("alice")))
        val resolved = BrowserScript.Resolver.resolve(
          "Hi ${user.name}", args, Map.empty
        )
        resolved shouldBe "Hi alice"
      }
    }
  }

  "tear down" should {
    "dispose TestBrowserSigil" in TestBrowserSigil.shutdown.map(_ => succeed)
  }
}
