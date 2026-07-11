package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.tool.web.{SearchProvider, SearchResult, TavilySearchDepth}
import spice.net.url

/**
 * Covers the framework-shipped [[SearchProvider]] factory surface
 * (bug #52). Live HTTP calls against real backends require API
 * keys / network and live separately; this spec covers:
 *
 *   - `custom` factory returns the supplied function's results,
 *   - `TavilySearchDepth` enum carries the right wire values,
 *   - every factory constructs without error against the default
 *     base URL (no compile-time guards, no hidden eager IO).
 *
 * Wire-shape coverage (URL / headers / body) for the live backends
 * is best exercised via integration suites with real keys; this
 * spec keeps the unit-level guard small.
 */
class SearchProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  "SearchProvider.custom" should {
    "return the supplied function's results verbatim" in {
      val provider = SearchProvider.custom { (query, max) =>
        Task.pure(List(
          SearchResult(title = s"hit-$query", url = "https://example.com", snippet = "snippet"),
          SearchResult(title = "second", url = "https://example.org", snippet = "more")
        ))
      }
      provider.search("scala 3 enums", maxResults = 5).map { results =>
        results should have size 2
        results.head.title shouldBe "hit-scala 3 enums"
        results(1).url shouldBe "https://example.org"
      }
    }

    "thread maxResults through to the function" in {
      val captured = new java.util.concurrent.atomic.AtomicInteger(0)
      val provider = SearchProvider.custom { (_, max) =>
        captured.set(max)
        Task.pure(Nil)
      }
      provider.search("anything", maxResults = 7).map { _ =>
        captured.get() shouldBe 7
      }
    }
  }

  "TavilySearchDepth" should {
    "carry the wire values Tavily expects" in {
      TavilySearchDepth.Fast.value shouldBe "fast"
      TavilySearchDepth.Basic.value shouldBe "basic"
      TavilySearchDepth.Advanced.value shouldBe "advanced"
      Task.unit.map(_ => succeed)
    }
  }

  "framework-shipped factories" should {
    "construct without error against default base URLs" in {
      // Smoke-test that each factory's eager arguments are accepted.
      // No requests are sent — `provider` is just instantiated.
      val brave = SearchProvider.brave(apiKey = "test-key")
      val google = SearchProvider.google(apiKey = "test-key", cx = "test-cx")
      val serpApi = SearchProvider.serpApi(apiKey = "test-key")
      val serper = SearchProvider.serper(apiKey = "test-key")
      val searxng = SearchProvider.searxng(baseUrl = url"https://searx.example.com")
      val tavily = SearchProvider.tavily(apiKey = "test-key")
      val ddg = SearchProvider.duckDuckGo()

      List(brave, google, serpApi, serper, searxng, tavily, ddg).foreach(_ should not be null)
      Task.unit.map(_ => succeed)
    }
  }

  "SearchResult" should {
    "default score and rawContent to None" in {
      val r = SearchResult(title = "t", url = "u", snippet = "s")
      r.score shouldBe None
      r.rawContent shouldBe None
      Task.unit.map(_ => succeed)
    }
  }
}
