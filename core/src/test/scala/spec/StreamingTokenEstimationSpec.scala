package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderEvent, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.provider.wire.OpenAIChatCompletions.{Config, StreamState}

/**
 * The OpenAI-compatible SSE wire decoder accumulates streamed
 * reasoning + content characters and emits synthetic
 * `ProviderEvent.Usage(TokenUsage(isEstimated = true, …))` events at
 * a steady cadence (250ms wall-clock OR 16 deltas since the last
 * emission, whichever fires first) so consumer UIs can render a
 * live token ticker during long reasoning streams instead of
 * jumping from the previous turn's total straight to the new total
 * at end-of-stream.
 *
 * The provider's authoritative usage chunk still arrives at the
 * end and carries `isEstimated = false`; the orchestrator's
 * `MessageDelta(usage = Some(...))` translation rides
 * `isEstimated` through unchanged so consumers that care about
 * estimate-vs-final can read the flag.
 */
class StreamingTokenEstimationSpec extends AnyWordSpec with Matchers {

  private val cfg: Config = Config(
    providerNamespace = "test",
    providerName      = "Test"
  )

  /** Mutable clock used so the time-based cadence trigger is
    * deterministic. Tests advance `nowNanos` between chunks. */
  private final class MutableClock(startNanos: Long = 0L) {
    private var current: Long = startNanos
    def advanceMs(ms: Long): Unit = current += ms * 1000000L
    def nowNanos(): Long = current
  }

  private def freshState(clock: MutableClock = new MutableClock()): StreamState =
    new StreamState(new ToolCallAccumulator(Vector.empty), nowNanos = () => clock.nowNanos())

  private def contentChunk(text: String): fabric.Json =
    JsonParser(s"""{"choices":[{"index":0,"delta":{"content":${fabric.io.JsonFormatter.Compact(fabric.str(text))}}}]}""")

  private def reasoningChunk(text: String): fabric.Json =
    JsonParser(s"""{"choices":[{"index":0,"delta":{"reasoning_content":${fabric.io.JsonFormatter.Compact(fabric.str(text))}}}]}""")

  private def usageChunk(prompt: Int, completion: Int): fabric.Json =
    JsonParser(s"""{"choices":[],"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":${prompt + completion}}}""")

  private def usageEvents(events: Vector[ProviderEvent]): Vector[ProviderEvent.Usage] =
    events.collect { case u: ProviderEvent.Usage => u }

  "OpenAIChatCompletions streaming token estimation" should {

    "emit synthetic isEstimated=true usage events during a stream before the final authoritative chunk" in {
      val clock = new MutableClock()
      val state = freshState(clock)
      val events = Vector.newBuilder[ProviderEvent]
      // 32 content deltas; advance clock 50ms per chunk. Total stream
      // duration 1.6s — both cadence triggers fire multiple times.
      (1 to 32).foreach { i =>
        events ++= OpenAIChatCompletions.parseChunk(contentChunk(s"chunk-$i "), state, cfg)
        clock.advanceMs(50)
      }
      events ++= OpenAIChatCompletions.parseChunk(usageChunk(100, 120), state, cfg)

      val allUsages = usageEvents(events.result())
      val estimated = allUsages.filter(_.usage.isEstimated)
      val authoritative = allUsages.filterNot(_.usage.isEstimated)

      withClue(s"all usages: $allUsages: ") {
        estimated should not be empty
        authoritative should have size 1
      }
      // Estimates must precede the final authoritative emission.
      val lastIdx = allUsages.indexOf(authoritative.head)
      estimated.foreach { e =>
        allUsages.indexOf(e) should be < lastIdx
      }
      // Consecutive synthetic emissions must report monotonically
      // increasing completion-token counts — the ticker's whole point.
      estimated.map(_.usage.completionTokens).sliding(2).foreach {
        case Seq(a, b) => a should be <= b
        case _         => ()
      }
    }

    "emit a final isEstimated=false usage event matching the provider's authoritative counts" in {
      val clock = new MutableClock()
      val state = freshState(clock)
      val events = Vector.newBuilder[ProviderEvent]
      (1 to 24).foreach { _ =>
        events ++= OpenAIChatCompletions.parseChunk(contentChunk("xxxxxxxxxx"), state, cfg)
        clock.advanceMs(40)
      }
      // Authoritative counts deliberately diverge from the heuristic
      // estimate so we can assert the final emission carries the
      // provider's number, not the wire decoder's guess.
      events ++= OpenAIChatCompletions.parseChunk(usageChunk(prompt = 250, completion = 999), state, cfg)

      val allUsages = usageEvents(events.result())
      val finalUsage = allUsages.last.usage
      finalUsage.isEstimated shouldBe false
      finalUsage.promptTokens shouldBe 250
      finalUsage.completionTokens shouldBe 999
      finalUsage.totalTokens shouldBe 1249
    }

    "fire the time-based 250ms cadence trigger for slow per-chunk streams" in {
      val clock = new MutableClock()
      val state = freshState(clock)
      val events = Vector.newBuilder[ProviderEvent]
      // Only 4 deltas total (well under the 16-delta trigger), but
      // each one advances the clock 300ms (well over the 250ms
      // trigger). Every chunk after the first should emit an
      // estimate.
      events ++= OpenAIChatCompletions.parseChunk(contentChunk("a"), state, cfg)
      clock.advanceMs(300)
      events ++= OpenAIChatCompletions.parseChunk(contentChunk("b"), state, cfg)
      clock.advanceMs(300)
      events ++= OpenAIChatCompletions.parseChunk(contentChunk("c"), state, cfg)
      clock.advanceMs(300)
      events ++= OpenAIChatCompletions.parseChunk(contentChunk("d"), state, cfg)

      val estimates = usageEvents(events.result()).filter(_.usage.isEstimated)
      withClue(s"estimates: $estimates: ") {
        estimates.size should be >= 2
      }
    }

    "fire the 16-delta chunk-count trigger for slow streams without 250ms elapsing" in {
      val clock = new MutableClock()
      val state = freshState(clock)
      val events = Vector.newBuilder[ProviderEvent]
      // 18 deltas, but the clock never advances — the time trigger
      // can't possibly fire. Only the 16-delta trigger surfaces the
      // estimate.
      (1 to 18).foreach { i =>
        events ++= OpenAIChatCompletions.parseChunk(reasoningChunk(s"r$i "), state, cfg)
      }
      val estimates = usageEvents(events.result()).filter(_.usage.isEstimated)
      withClue(s"estimates: $estimates: ") {
        estimates.size shouldBe 1
        estimates.head.usage.completionTokens should be > 0
      }
    }

    "preserve back-compat — a single combined content+usage chunk emits exactly one usage event with isEstimated=false" in {
      // A "non-streaming" shape from the wire decoder's view: a
      // single chunk arrives carrying both content and the usage
      // block in one payload. No prior deltas means no cadence
      // trigger; the only usage on the wire is the authoritative
      // one. The historical guarantee — exactly one Usage event
      // per response when no estimates accumulate — must hold.
      val state = freshState()
      val combinedChunk = JsonParser(
        """{"choices":[{"index":0,"delta":{"content":"hello world"}}],
          | "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}""".stripMargin
      )
      val events = OpenAIChatCompletions.parseChunk(combinedChunk, state, cfg)
      val usages = usageEvents(events)
      usages should have size 1
      usages.head.usage.isEstimated shouldBe false
      usages.head.usage.completionTokens shouldBe 3
      usages.head.usage.promptTokens shouldBe 12
    }
  }
}
