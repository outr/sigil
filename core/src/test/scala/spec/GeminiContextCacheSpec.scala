package spec

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import sigil.provider.*
import sigil.provider.google.{GeminiContextCache, Google, GoogleProvider}
import spice.net.*

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import sigil.tool.ToolRoster

/**
 * Offline coverage for Gemini explicit `cachedContents` context
 * caching. A JDK in-process `HttpServer` stands in for the Google
 * generative-language endpoint: it answers `cachedContents` creates
 * (counting them) and `streamGenerateContent` requests (returning an
 * SSE chunk with `cachedContentTokenCount` in `usageMetadata`).
 *
 * Asserts the full lifecycle: a stable prefix above the threshold
 * creates and references a resource; a second request with the same
 * prefix reuses it with no second create; a sub-threshold prefix and
 * a disabled toggle both fall back to inline; `cachedContentTokenCount`
 * lands in [[sigil.provider.TokenUsage.cacheReadTokens]].
 */
class GeminiContextCacheSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  TestSigil.initFor(getClass.getSimpleName)

  /** Count of `cachedContents.create` POSTs the stub has served. */
  private val createCount = new AtomicInteger(0)

  private val server: HttpServer = {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    // cachedContents.create — POST /v1beta/cachedContents
    s.createContext("/v1beta/cachedContents", new HttpHandler {
      override def handle(ex: HttpExchange): Unit = {
        ex.getRequestBody.readAllBytes()
        val n = createCount.incrementAndGet()
        val responseBody = s"""{"name":"cachedContents/stub-resource-$n"}"""
        val bytes = responseBody.getBytes("UTF-8")
        ex.getResponseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val os = ex.getResponseBody; os.write(bytes); os.close()
      }
    })
    // streamGenerateContent — POST /v1beta/models/{model}:streamGenerateContent
    s.createContext("/v1beta/models", new HttpHandler {
      override def handle(ex: HttpExchange): Unit = {
        ex.getRequestBody.readAllBytes()
        val chunk =
          """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],""" +
            """"usageMetadata":{"promptTokenCount":5000,"candidatesTokenCount":10,""" +
            """"totalTokenCount":5010,"cachedContentTokenCount":4800}}"""
        val sse = s"data: $chunk\n\n"
        val bytes = sse.getBytes("UTF-8")
        ex.getResponseHeaders.set("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val os = ex.getResponseBody; os.write(bytes); os.close()
      }
    })
    s.start()
    s
  }

  private val baseUrl: URL = URL.parse(s"http://127.0.0.1:${server.getAddress.getPort}")

  override protected def afterAll(): Unit = {
    server.stop(0)
    super.afterAll()
  }

  /** A cache-capable Gemini model id. */
  private def cacheCapableModel: Id[Model] = Model.id("google", "gemini-2.5-flash")

  /** A system prompt large enough to exceed the 4096-token caching
    * threshold under the heuristic tokenizer (~2 tokens per 7 chars). */
  private def largeSystem: String = "stable-prefix-line content here. " * 800

  private def callWith(system: String, modelId: Id[Model] = cacheCapableModel): ProviderCall =
    ProviderCall(
      model = TestSigil.testModel(modelId),
      system = system,
      messages = Vector(ProviderMessage.User(Vector(MessageContent.Text("hello")))),
      roster = ToolRoster(Vector.empty),
      builtInTools = Set.empty,
      toolChoice = ToolChoice.Auto,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50))
    )

  private def bodyOf(provider: GoogleProvider, call: ProviderCall): fabric.Json = {
    val req = provider.httpRequestFor(call).sync()
    req.content match {
      case Some(c: spice.http.content.StringContent) => fabric.io.JsonParser(c.value)
      case _ => fabric.obj()
    }
  }

  "GeminiContextCache (the in-process registry)" should {
    "store and look up a live entry by prefix hash" in {
      val cache = new GeminiContextCache
      val key = GeminiContextCache.hashOf("system text", "tools block")
      cache.lookup(key) shouldBe None
      cache.store(key, "cachedContents/abc", 10.minutes)
      cache.lookup(key).map(_.resourceName) shouldBe Some("cachedContents/abc")
      cache.size shouldBe 1
    }

    "treat an expired entry as absent and evict it" in {
      val cache = new GeminiContextCache
      val key = GeminiContextCache.hashOf("s", "t")
      // Store with a TTL well inside the expiry safety margin so the
      // entry reads as already lapsed.
      cache.store(key, "cachedContents/stale", 1.second)
      cache.lookup(key) shouldBe None
      cache.size shouldBe 0
    }

    "yield the same hash for byte-identical prefixes and distinct hashes otherwise" in {
      GeminiContextCache.hashOf("a", "b") shouldBe GeminiContextCache.hashOf("a", "b")
      GeminiContextCache.hashOf("a", "b") should not be GeminiContextCache.hashOf("a", "c")
      GeminiContextCache.hashOf("ab", "") should not be GeminiContextCache.hashOf("a", "b")
    }
  }

  "GoogleProvider explicit context caching (enabled, above threshold)" should {
    "create a cachedContent resource and reference it, omitting the inline system instruction" in {
      createCount.set(0)
      val provider = GoogleProvider(apiKey = "test-key", sigilRef = TestSigil, baseUrl = baseUrl)
      val body = bodyOf(provider, callWith(largeSystem))
      createCount.get() shouldBe 1
      body.get("cachedContent").map(_.asString) shouldBe Some("cachedContents/stub-resource-1")
      body.get("systemInstruction") shouldBe None
    }

    "reuse the cached resource on a second request with the same prefix (no second create)" in {
      createCount.set(0)
      val provider = GoogleProvider(apiKey = "test-key", sigilRef = TestSigil, baseUrl = baseUrl)
      val first = bodyOf(provider, callWith(largeSystem))
      val second = bodyOf(provider, callWith(largeSystem))
      createCount.get() shouldBe 1
      first.get("cachedContent") shouldBe second.get("cachedContent")
    }

    "create a fresh resource when the stable prefix changes" in {
      createCount.set(0)
      val provider = GoogleProvider(apiKey = "test-key", sigilRef = TestSigil, baseUrl = baseUrl)
      bodyOf(provider, callWith(largeSystem))
      bodyOf(provider, callWith(largeSystem + " extra distinct tail content here. " * 50))
      createCount.get() shouldBe 2
    }
  }

  "GoogleProvider explicit context caching (sub-threshold)" should {
    "send a small prefix inline with no cachedContents.create" in {
      createCount.set(0)
      val provider = GoogleProvider(apiKey = "test-key", sigilRef = TestSigil, baseUrl = baseUrl)
      val body = bodyOf(provider, callWith("a tiny system prompt"))
      createCount.get() shouldBe 0
      body.get("cachedContent") shouldBe None
      body.get("systemInstruction") should not be None
    }
  }

  "GoogleProvider explicit context caching (disabled toggle)" should {
    "skip caching entirely and send the prefix inline even when it is large" in {
      createCount.set(0)
      val provider = GoogleProvider(
        apiKey = "test-key",
        sigilRef = TestSigil,
        baseUrl = baseUrl,
        contextCaching = false
      )
      val body = bodyOf(provider, callWith(largeSystem))
      createCount.get() shouldBe 0
      body.get("cachedContent") shouldBe None
      body.get("systemInstruction") should not be None
    }
  }

  "GoogleProvider explicit context caching (non-Gemini model)" should {
    "skip caching for a model routed through the Google wire that is not cache-capable" in {
      createCount.set(0)
      val provider = GoogleProvider(apiKey = "test-key", sigilRef = TestSigil, baseUrl = baseUrl)
      val body = bodyOf(provider, callWith(largeSystem, Model.id("google", "palm-2-legacy")))
      createCount.get() shouldBe 0
      body.get("cachedContent") shouldBe None
    }
  }

  "Google supportsContextCaching" should {
    "accept stable Gemini 2.x models and reject experimental / preview / non-Gemini" in {
      Google.supportsContextCaching("gemini-2.5-flash") shouldBe true
      Google.supportsContextCaching("gemini-2.0-flash") shouldBe true
      Google.supportsContextCaching("gemini-2.5-flash-exp") shouldBe false
      Google.supportsContextCaching("gemini-2.5-pro-preview") shouldBe false
      Google.supportsContextCaching("palm-2-legacy") shouldBe false
    }
  }

  "Gemini cached-token usage parsing" should {
    "land cachedContentTokenCount in TokenUsage.cacheReadTokens via CacheKeys.Google" in {
      val usage = fabric.obj(
        "promptTokenCount" -> fabric.num(5000),
        "candidatesTokenCount" -> fabric.num(10),
        "totalTokenCount" -> fabric.num(5010),
        "cachedContentTokenCount" -> fabric.num(4800)
      )
      val parsed = TokenUsage.fromJson(
        usage,
        "promptTokenCount",
        "candidatesTokenCount",
        Some("totalTokenCount"),
        CacheKeys.Google
      )
      parsed.promptTokens shouldBe 5000
      parsed.cacheReadTokens shouldBe 4800
      parsed.cacheCreationTokens shouldBe 0
    }

    "surface cacheReadTokens on the Usage event from a streamed Gemini response" in {
      val provider = GoogleProvider(apiKey = "test-key", sigilRef = TestSigil, baseUrl = baseUrl)
      val events = provider.call(callWith(largeSystem)).toList.sync()
      val usage = events.collectFirst { case ProviderEvent.Usage(u) => u }
      usage.map(_.cacheReadTokens) shouldBe Some(4800)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
