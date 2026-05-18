package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, TurnContext}
import sigil.browser.WebBrowserMode
import sigil.browser.tool.{
  BrowserClickTool, BrowserNavigateTool, BrowserSaveHtmlTool, BrowserScreenshotTool,
  BrowserScrollTool, BrowserTextSearchTool, BrowserTypeTool, BrowserXPathQueryTool,
  CreateBrowserScriptTool, DeleteBrowserScriptTool, ListBrowserScriptsTool, UpdateBrowserScriptTool
}
import sigil.provider.{ConversationMode, Mode}
import sigil.tool.{DiscoveryFilter, DiscoveryRequest, Tool}

/**
 * Regression coverage for the wire-log scenario reproduced by bug
 * #122. The user is in `ConversationMode`, the agent calls
 * `find_capability("read")`, the framework lists `browser_click`
 * as `Ready`, and the agent fires `browser_click({"selector":
 * ".read"})` instead of `change_mode("web-browser")` or a real
 * filesystem tool.
 *
 * Fix: every browser tool sets `modes = Set(WebBrowserMode.id)`.
 * [[DiscoveryFilter.passesAffinity]] already honors empty-as-
 * universal vs non-empty-as-restricted, so find_capability filters
 * them out of every non-web-browser mode.
 */
class BrowserModeAffinitySpec extends AnyWordSpec with Matchers {

  private val browserTools: List[Tool] = List(
    new BrowserClickTool,
    new BrowserNavigateTool,
    new BrowserSaveHtmlTool,
    new BrowserScreenshotTool,
    new BrowserScrollTool,
    new BrowserTextSearchTool,
    new BrowserTypeTool,
    new BrowserXPathQueryTool,
    CreateBrowserScriptTool,
    DeleteBrowserScriptTool,
    ListBrowserScriptsTool,
    UpdateBrowserScriptTool
  )

  private def request(mode: Mode): DiscoveryRequest =
    DiscoveryRequest(
      keywords = "anything",
      chain = Nil,
      mode = mode,
      callerSpaces = Set(GlobalSpace)
    )

  "Every browser tool" should {

    "declare modes = Set(WebBrowserMode.id) so it's only discoverable inside web-browser mode" in
      browserTools.foreach { t =>
        withClue(s"${t.name.value}: ") {
          t.modes shouldBe Set(WebBrowserMode.id)
        }
      }

    "pass affinity in WebBrowserMode" in
      browserTools.foreach { t =>
        withClue(s"${t.name.value} in WebBrowserMode: ") {
          DiscoveryFilter.passesAffinity(t, request(WebBrowserMode)) shouldBe true
        }
      }

    "FAIL affinity in ConversationMode (the wire-log #122 scenario)" in
      browserTools.foreach { t =>
        withClue(s"${t.name.value} in ConversationMode: ") {
          DiscoveryFilter.passesAffinity(t, request(ConversationMode)) shouldBe false
        }
      }
  }
}
