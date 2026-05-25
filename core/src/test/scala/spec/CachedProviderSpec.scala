package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.Model
import sigil.provider.cache.{CachedResponse, CacheMode, CacheStore, FileSystemCacheStore, MissingCacheException, RequestCacheKey}
import sigil.provider.{
  CachedProvider, GenerationSettings, OneShotRequest, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tool.core.RespondTool
import spice.http.HttpRequest

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * Verifies [[CachedProvider]] record / replay semantics and the
 * canonicalization rules baked into [[RequestCacheKey]].
 */
class CachedProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "cache-fixture-model")

  /** In-memory [[CacheStore]] used by the unit specs — round-trips
    * through the same JSONL serialisation as [[FileSystemCacheStore]]
    * so the spec exercises real codec behaviour without touching the
    * filesystem. */
  private class MemoryCacheStore extends CacheStore {
    private val entries = new java.util.concurrent.ConcurrentHashMap[String, String]
    override def read(keyHash: String): Option[CachedResponse] =
      Option(entries.get(keyHash)).map(CachedResponse.fromJsonl)
    override def write(keyHash: String, response: CachedResponse): Unit = {
      entries.put(keyHash, CachedResponse.toJsonl(response))
      ()
    }
    def hasKey(keyHash: String): Boolean = entries.containsKey(keyHash)
    def keys: Set[String] = {
      import scala.jdk.CollectionConverters.*
      entries.keySet().asScala.toSet
    }
  }

  /** Stub provider that returns a fixed stream and counts calls. */
  private class CountingProvider(perAttempt: Int => Stream[ProviderEvent]) extends Provider {
    val attemptCount: AtomicInteger = new AtomicInteger(0)
    val observedCalls: AtomicReference[List[ProviderCall]] = new AtomicReference(List.empty)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("stub provider — no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = attemptCount.incrementAndGet()
      observedCalls.updateAndGet(calls => calls :+ input)
      perAttempt(n)
    }
  }

  private def successStream: Stream[ProviderEvent] =
    Stream.emits(List(
      ProviderEvent.TextDelta("hello world"),
      ProviderEvent.Done(StopReason.Complete)
    ))

  private def oneShot(systemPrompt: String = "You are a helpful test assistant.",
                      userPrompt: String = "What is 2+2?",
                      tools: Vector[Tool] = Vector.empty): OneShotRequest =
    OneShotRequest(
      model            = TestSigil.testModel(modelId),
      systemPrompt       = systemPrompt,
      userPrompt         = userPrompt,
      tools              = tools,
      generationSettings = GenerationSettings(temperature = Some(0.0))
    )

  "CachedProvider" should {

    "record on first call and replay on subsequent calls with the same canonical request" in {
      val store = new MemoryCacheStore
      val underlying = new CountingProvider(_ => successStream)
      val cached = new CachedProvider(underlying, TestSigil, store, CacheMode.RecordOrReplay)
      val request = oneShot()
      for {
        firstEvents  <- cached(request).toList
        secondEvents <- cached(request).toList
      } yield {
        underlying.attemptCount.get() shouldBe 1
        firstEvents shouldBe secondEvents
        firstEvents.last shouldBe a[ProviderEvent.Done]
        store.keys.size shouldBe 1
      }
    }

    "hash identically when two semantic-identical ProviderCalls differ only in per-call ids" in {
      val callA = ProviderCall(
        model = TestSigil.testModel(modelId),
        system = "system",
        messages = Vector(
          sigil.provider.ProviderMessage.User("hi"),
          sigil.provider.ProviderMessage.Assistant(
            content = "thinking",
            toolCalls = List(sigil.provider.ToolCallMessage(id = "id-abc-123", name = "respond", argsJson = """{"content":"hi"}"""))
          ),
          sigil.provider.ProviderMessage.ToolResult(toolCallId = "id-abc-123", content = "ok")
        ),
        tools = Vector.empty,
        builtInTools = Set.empty,
        toolChoice = sigil.provider.ToolChoice.Auto,
        generationSettings = GenerationSettings(temperature = Some(0.0))
      )
      val callB = callA.copy(messages = Vector(
        sigil.provider.ProviderMessage.User("hi"),
        sigil.provider.ProviderMessage.Assistant(
          content = "thinking",
          toolCalls = List(sigil.provider.ToolCallMessage(id = "id-zzz-999", name = "respond", argsJson = """{"content":"hi"}"""))
        ),
        sigil.provider.ProviderMessage.ToolResult(toolCallId = "id-zzz-999", content = "ok")
      ))
      Task.pure(
        RequestCacheKey.canonical(callA).sha256 shouldBe RequestCacheKey.canonical(callB).sha256
      )
    }

    "miss the cache when the system prompt changes by one word" in {
      val store = new MemoryCacheStore
      val underlying = new CountingProvider(_ => successStream)
      val cached = new CachedProvider(underlying, TestSigil, store, CacheMode.RecordOrReplay)
      val first  = oneShot(systemPrompt = "You are a helpful test assistant.")
      val second = oneShot(systemPrompt = "You are a friendly test assistant.")
      for {
        _ <- cached(first).toList
        _ <- cached(second).toList
      } yield {
        underlying.attemptCount.get() shouldBe 2
        store.keys.size shouldBe 2
      }
    }

    "miss the cache when a tool's description changes" in {
      val store = new MemoryCacheStore
      val underlying = new CountingProvider(_ => successStream)
      val cached = new CachedProvider(underlying, TestSigil, store, CacheMode.RecordOrReplay)
      // Two tool rosters with the same shape but a one-word difference
      // in description. The hash must change.
      val toolOriginal = new ProxyToolWithDescription(RespondTool, "Send a textual response back to the user.")
      val toolReworded = new ProxyToolWithDescription(RespondTool, "Send a written response back to the user.")
      val first  = oneShot(tools = Vector(toolOriginal))
      val second = oneShot(tools = Vector(toolReworded))
      for {
        _ <- cached(first).toList
        _ <- cached(second).toList
      } yield {
        underlying.attemptCount.get() shouldBe 2
        store.keys.size shouldBe 2
      }
    }

    "raise MissingCacheException in ReplayOnly mode when no fixture exists" in {
      import scala.util.{Failure, Success}
      val store = new MemoryCacheStore
      val underlying = new CountingProvider(_ => successStream)
      val cached = new CachedProvider(underlying, TestSigil, store, CacheMode.ReplayOnly)
      cached(oneShot()).toList.attempt.map {
        case Failure(_: MissingCacheException) =>
          underlying.attemptCount.get() shouldBe 0
          succeed
        case Failure(other) => fail(s"expected MissingCacheException, got ${other.getClass.getName}: ${other.getMessage}")
        case Success(events) => fail(s"expected failure, got events: $events")
      }
    }

    "not cache a failed stream — the next call re-hits the underlying provider" in {
      val store = new MemoryCacheStore
      val errorStream: Stream[ProviderEvent] = Stream.emit(()).evalMap[ProviderEvent](_ =>
        Task.error[ProviderEvent](new RuntimeException("synthetic mid-stream failure")))
      val underlying = new CountingProvider(_ => errorStream)
      val cached = new CachedProvider(underlying, TestSigil, store, CacheMode.RecordOrReplay)
      val request = oneShot()
      for {
        firstAttempt  <- cached(request).toList.attempt
        secondAttempt <- cached(request).toList.attempt
      } yield {
        firstAttempt.isFailure shouldBe true
        secondAttempt.isFailure shouldBe true
        underlying.attemptCount.get() should be >= 2
        store.keys shouldBe empty
      }
    }

    "round-trip JSONL through FileSystemCacheStore" in Task {
      val tmpDir = java.nio.file.Files.createTempDirectory("cached-provider-spec")
      try {
        val store = new FileSystemCacheStore(tmpDir)
        val response = CachedResponse(
          requestHash = "abc123",
          recordedAt = lightdb.time.Timestamp(1_700_000_000_000L),
          events = List(
            ProviderEvent.TextDelta("hi"),
            ProviderEvent.Usage(sigil.provider.TokenUsage(promptTokens = 5, completionTokens = 7, totalTokens = 12)),
            ProviderEvent.Done(StopReason.Complete)
          )
        )
        store.write("abc123", response)
        val readBack = store.read("abc123")
        readBack should not be empty
        readBack.get.requestHash shouldBe "abc123"
        readBack.get.events shouldBe response.events
      } finally {
        // Cleanup the temp dir
        if (java.nio.file.Files.exists(tmpDir)) {
          val walker = java.nio.file.Files.walk(tmpDir)
          try {
            import scala.jdk.CollectionConverters.*
            walker.iterator().asScala.toList.reverse.foreach { p =>
              java.nio.file.Files.deleteIfExists(p); ()
            }
          } finally walker.close()
        }
      }
    }
  }

  /** Wraps an existing [[Tool]] but overrides its `description`. Lets
    * us assert that a description-only change perturbs the cache hash
    * without faking out a new schema. */
  private class ProxyToolWithDescription(delegate: Tool, descriptionText: String) extends Tool {
    type Input  = ToolInput
    type Output = sigil.tool.TextToolOutput
    val inputRW: fabric.rw.RW[ToolInput] = delegate.inputRW.asInstanceOf[fabric.rw.RW[ToolInput]]
    val outputRW = summon[fabric.rw.RW[sigil.tool.TextToolOutput]]
    val name: ToolName = delegate.name
    val description: String = descriptionText
    override def executeResult(input: ToolInput,
                               context: ToolContext): Task[sigil.tool.ToolResult[sigil.tool.TextToolOutput]] =
      Task.pure(sigil.tool.ToolResult.Success(sigil.tool.TextToolOutput("")))
    override def paginate: Boolean = delegate.paginate
    override def inputDefinition: fabric.define.Definition = delegate.inputDefinition
  }
}
