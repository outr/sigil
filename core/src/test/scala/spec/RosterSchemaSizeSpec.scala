package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.{Stream, Task}
import sigil.event.Event
import sigil.provider.{ConversationMode, Provider, ProviderCall, ProviderType}
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import sigil.tool.{ToolInput, ToolName, TypedTool}

/**
 * Coverage for sigil bug #43 — `Provider.estimateRoster` must include
 * the rendered JSON parameter schema body in its per-tool token
 * count, not just `name + description + 30`. Realistic input schemas
 * are hundreds-to-thousands of tokens; the prior estimate was off by
 * an order of magnitude for tools with rich parameters, letting
 * oversized requests slip past the pre-flight gate.
 */
class RosterSchemaSizeSpec extends AnyWordSpec with Matchers {

  case class WideInput(field01: String = "",
                       field02: String = "",
                       field03: String = "",
                       field04: String = "",
                       field05: String = "",
                       field06: String = "",
                       field07: String = "",
                       field08: String = "",
                       field09: String = "",
                       field10: String = "",
                       field11: String = "",
                       field12: String = "") extends ToolInput derives RW

  case object WideTool extends TypedTool[WideInput](
    name        = ToolName("wide_tool"),
    description = "A short description.",
    keywords    = Set.empty
  ) {
  override def paginate: Boolean = false

    override protected def executeTyped(input: WideInput, context: sigil.TurnContext): Stream[Event] = Stream.empty
  }

  // Test-friendly Provider exposing the protected `estimateRoster`.
  private object TestProvider extends Provider {
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def `type`: ProviderType = ProviderType.OpenAI
    override def call(input: ProviderCall): Stream[_root_.sigil.provider.ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new RuntimeException("not implemented"))
    def public(tools: Vector[_root_.sigil.tool.Tool], tok: Tokenizer): Int = estimateRoster(tools, tok)
  }

  "Provider.estimateRoster" should {
    "count more than the legacy `name + description + 30` for a tool with a non-trivial schema" in {
      val tok = HeuristicTokenizer
      // Legacy approximation, what the framework used before bug #43.
      val legacy = tok.count(WideTool.schema.name.value) +
        tok.count(WideTool.descriptionFor(_root_.sigil.provider.ConversationMode, TestSigil)) + 30
      val current = TestProvider.public(Vector(WideTool), tok)
      current should be > legacy
      // Conservative lower bound — 12 fields × ~12 tokens of schema each
      // ≈ 140 minimum. Schema body is the dominant cost here; the
      // wrapper + name + description add a small constant on top.
      current should be >= 60
    }

    "scale linearly with the number of tools" in {
      val tok = HeuristicTokenizer
      val one = TestProvider.public(Vector(WideTool), tok)
      val three = TestProvider.public(Vector(WideTool, WideTool, WideTool), tok)
      // Allow tokenizer integer rounding to introduce a small
      // multiplier wobble; assert close enough to 3×.
      three.toDouble should be > (one * 2.5)
      three.toDouble should be < (one * 3.5)
    }

    "return 0 for an empty roster" in {
      TestProvider.public(Vector.empty, HeuristicTokenizer) shouldBe 0
    }
  }
}
