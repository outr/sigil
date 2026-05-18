package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.provider.{ProviderCall, ProviderMessage, ToolChoice, ToolCallMessage, ConversationMode}
import sigil.provider.GenerationSettings
import spice.net.url

/**
 * Coverage for sigil bug #46 — `LlamaCppProvider.estimateRequest`
 * should call `/apply-template` + tokenize the chat-template-rendered
 * prompt (capturing template glue between messages) but must fall
 * back to the piecewise default when the backend is unreachable.
 *
 * Live `/apply-template` accuracy is exercised by the existing
 * `LlamaCpp*Spec` suites that opt in when the backend is reachable;
 * this unit test verifies the fallback path.
 */
class LlamaCppEstimateRequestSpec extends AnyWordSpec with Matchers {

  // Definitely-unreachable port forces the `/apply-template`
  // round-trip to fail; the override falls back to `super.estimateRequest`.
  // Test-friendly subclass exposing the protected hook.
  private class ExposedProvider
    extends LlamaCppProvider(
      url = url"http://127.0.0.1:1",
      models = Nil,
      sigilRef = TestSigil
    ) {
    def measure(call: ProviderCall): Int = estimateRequest(call)
  }
  private val exposed = new ExposedProvider

  private val sampleCall = ProviderCall(
    modelId = Id[Model]("llamacpp/test"),
    system = "You are a helpful assistant.",
    messages = Vector(
      ProviderMessage.User("What is 2+2?"),
      ProviderMessage.Assistant(
        content = "Let me think.",
        toolCalls = List(ToolCallMessage(id = "call-1", name = "compute", argsJson = "{\"x\":2,\"y\":2}"))
      ),
      ProviderMessage.ToolResult(toolCallId = "call-1", content = "4")
    ),
    tools = Vector.empty,
    builtInTools = Set.empty,
    toolChoice = ToolChoice.None,
    generationSettings = GenerationSettings(),
    currentMode = ConversationMode
  )

  "LlamaCppProvider.estimateRequest" should {
    "fall back to the piecewise default when /apply-template is unreachable" in {
      val estimated = exposed.measure(sampleCall)
      estimated should be > 0
    }
  }
}
