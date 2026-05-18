package sigil.provider.digitalocean

import fabric.{Null, arr, num, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.{ProviderStreamException, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions

/**
 * Coverage for Bug #161 — when DO's `kimi-k2.5` deployment burns its
 * entire `max_tokens` budget without emitting any content text OR tool
 * calls (the two observed degeneration modes: reasoning-only `" The!!!"`
 * garbage, or `content: null` padding), the framework's wire layer
 * raises a [[ProviderStreamException]] so `ProviderStrategy` can route
 * to the next candidate instead of surfacing an empty turn.
 *
 * Drives [[OpenAIChatCompletions.parseLine]] directly — no HTTP server.
 */
class DigitalOceanEmptyBudgetBurnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  spec.TestSigil.initFor(getClass.getSimpleName)

  private val doConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = DigitalOcean.Provider,
    providerName = "DigitalOcean",
    emptyBudgetBurnThrows = true
  )

  private val controlConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = "control",
    providerName = "Control",
    emptyBudgetBurnThrows = false
  )

  /**
   * Drive a sequence of SSE lines through one StreamState.
   */
  private def drive(lines: Vector[String], config: OpenAIChatCompletions.Config): Vector[sigil.provider.ProviderEvent] = {
    val state = new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(Vector.empty))
    lines.flatMap(line => OpenAIChatCompletions.parseLine(line, state, config))
  }

  private def dataLine(j: fabric.Json): String =
    "data: " + fabric.io.JsonFormatter.Compact(j)

  /**
   * A chunk that streams a finish_reason without any delta content.
   */
  private val finishLengthChunk = obj(
    "choices" -> arr(
      obj(
        "delta" -> obj("content" -> Null, "reasoning_content" -> Null),
        "finish_reason" -> str("length"),
        "index" -> num(0)
      )
    )
  )

  /**
   * A chunk with a non-empty reasoning_content delta only.
   */
  private val reasoningOnlyChunk = obj(
    "choices" -> arr(
      obj(
        "delta" -> obj("reasoning_content" -> str(" The!!!!!!!!!!!")),
        "index" -> num(0)
      )
    )
  )

  /**
   * Chunk that emits a normal content delta.
   */
  private val contentChunk = obj(
    "choices" -> arr(
      obj(
        "delta" -> obj("content" -> str("hello")),
        "index" -> num(0)
      )
    )
  )

  /**
   * Chunk that streams a tool call.
   */
  private def toolCallChunk(callId: String, name: String) = obj(
    "choices" -> arr(
      obj(
        "delta" -> obj(
          "tool_calls" -> arr(
            obj(
              "index" -> num(0),
              "id" -> str(callId),
              "type" -> str("function"),
              "function" -> obj("name" -> str(name), "arguments" -> str("{}"))
            )
          )
        ),
        "index" -> num(0)
      )
    )
  )

  "OpenAIChatCompletions empty-budget-burn detection (Bug #161)" should {

    "throw ProviderStreamException when finish=length arrives with no content and no tool calls" in {
      val thrown = intercept[ProviderStreamException] {
        drive(
          Vector(
            dataLine(reasoningOnlyChunk),
            dataLine(finishLengthChunk),
            "data: [DONE]"
          ),
          doConfig
        )
      }
      thrown.providerKey shouldBe "digitalocean"
      thrown.code shouldBe 200
      thrown.typ shouldBe "empty_budget_burn"
      thrown.message_ should include("max_tokens budget")
    }

    "throw on the null-padded variant (no reasoning_content, no content, no tool_calls, finish=length)" in {
      val thrown = intercept[ProviderStreamException] {
        drive(
          Vector(
            dataLine(finishLengthChunk),
            "data: [DONE]"
          ),
          doConfig
        )
      }
      thrown.providerKey shouldBe "digitalocean"
      thrown.typ shouldBe "empty_budget_burn"
    }

    "NOT throw when content was emitted, even if finish=length" in {
      noException should be thrownBy drive(
        Vector(
          dataLine(contentChunk),
          dataLine(finishLengthChunk),
          "data: [DONE]"
        ),
        doConfig
      )
      rapid.Task.pure(succeed)
    }

    "NOT throw when a tool call was emitted, even if finish=length" in {
      noException should be thrownBy drive(
        Vector(
          dataLine(toolCallChunk("c1", "respond")),
          dataLine(finishLengthChunk),
          "data: [DONE]"
        ),
        doConfig
      )
      rapid.Task.pure(succeed)
    }

    "NOT throw when finish=stop (only length burns budget; stop is the normal silent-turn path Bug #46 handles)" in {
      val stopChunk = obj(
        "choices" -> arr(
          obj(
            "delta" -> obj("content" -> Null),
            "finish_reason" -> str("stop"),
            "index" -> num(0)
          )
        )
      )
      noException should be thrownBy drive(
        Vector(
          dataLine(stopChunk),
          "data: [DONE]"
        ),
        doConfig
      )
      rapid.Task.pure(succeed)
    }

    "NOT throw when emptyBudgetBurnThrows is disabled (opt-in only)" in {
      noException should be thrownBy drive(
        Vector(
          dataLine(reasoningOnlyChunk),
          dataLine(finishLengthChunk),
          "data: [DONE]"
        ),
        controlConfig
      )
      rapid.Task.pure(succeed)
    }

    "default ErrorClassifier classifies the empty_budget_burn exception as Fallthrough" in {
      val exc = new ProviderStreamException(
        "digitalocean",
        200,
        "empty_budget_burn",
        "DigitalOcean consumed max_tokens budget without emitting any content or tool calls")
      sigil.provider.ErrorClassifier.Default.classify(exc) shouldBe sigil.provider.ErrorClassification.Fallthrough
    }
  }

  "tear down" should {
    "dispose TestSigil" in spec.TestSigil.shutdown.map(_ => succeed)
  }
}
