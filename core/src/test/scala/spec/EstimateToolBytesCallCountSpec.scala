package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.{Stream, Task}
import sigil.event.Event
import sigil.provider.{Provider, ProviderCall, ProviderType}
import sigil.tokenize.Tokenizer
import sigil.tool.{ToolInput, ToolName, TypedTool}

import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for sigil bug #47 — `Provider.estimateToolBytes` should
 * concatenate per-tool wire bytes into ONE tokenizer call, not three
 * (name / description / schema). Providers whose tokenizer makes an
 * HTTP round-trip (`LlamaCppTokenizer`) hit this 3× per tool when
 * pre-flight runs the roster pass; the cumulative latency stalls
 * real user turns when stale-pool resets retry-block.
 */
class EstimateToolBytesCallCountSpec extends AnyWordSpec with Matchers {

  case class WideInput(field01: String = "",
                       field02: String = "",
                       field03: String = "",
                       field04: String = "") extends ToolInput derives RW

  case object WideTool extends TypedTool[WideInput](
    name        = ToolName("wide_tool"),
    description = "A short description.",
    keywords    = Set.empty
  ) {
  override def paginate: Boolean = false

    override protected def executeTyped(input: WideInput, context: sigil.TurnContext): Stream[Event] = Stream.empty
  }

  // Counting tokenizer that records every call.
  private class CountingTokenizer extends Tokenizer {
    val calls: AtomicInteger = new AtomicInteger(0)
    override def count(text: String): Int = {
      calls.incrementAndGet()
      math.max(1, text.length / 4)
    }
  }

  private object TestProvider extends Provider {
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def `type`: ProviderType = ProviderType.OpenAI
    override def call(input: ProviderCall): Stream[_root_.sigil.provider.ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new RuntimeException("not implemented"))
    def estimateRosterPub(tools: Vector[_root_.sigil.tool.Tool], tok: Tokenizer): Int =
      estimateRoster(tools, tok)
  }

  "Provider.estimateToolBytes" should {
    "make exactly one tokenizer call per tool (concat-then-count, not three separate counts)" in {
      val tok = new CountingTokenizer
      TestProvider.estimateRosterPub(Vector(WideTool), tok)
      tok.calls.get shouldBe 1
    }

    "scale per-tool linearly: N tools → N tokenizer calls" in {
      val tok = new CountingTokenizer
      TestProvider.estimateRosterPub(Vector(WideTool, WideTool, WideTool, WideTool, WideTool), tok)
      tok.calls.get shouldBe 5
    }

    "make zero tokenizer calls for an empty roster" in {
      val tok = new CountingTokenizer
      TestProvider.estimateRosterPub(Vector.empty, tok)
      tok.calls.get shouldBe 0
    }
  }
}
