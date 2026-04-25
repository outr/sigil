package spec

import lightdb.id.Id
import sigil.conversation.TurnInput
import sigil.db.Model
import sigil.provider.{GenerationSettings, Provider}
import sigil.provider.llamacpp.LlamaCppProvider

/**
 * LlamaCpp-specific coverage: the shared marker-based tests come from
 * [[AbstractRequestCoverageSpec]]. This class adds tests that assert
 * concrete chat-completions wire keys that are specific to the
 * LlamaCpp / OpenAI-compatible chat-completions format.
 */
class LlamaCppRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
  override protected def modelId: Id[Model] = Model.id("test", "model")

  "LlamaCppProvider wire-specific coverage" should {
    "forward topP from GenerationSettings as top_p on the wire" in {
      val body = bodyOf(
        TurnInput(emptyView),
        GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0), topP = Some(0.42))
      )
      body should include("\"top_p\":0.42")
    }

    "forward stopSequences from GenerationSettings as stop on the wire" in {
      val body = bodyOf(
        TurnInput(emptyView),
        GenerationSettings(
          maxOutputTokens = Some(50),
          temperature = Some(0.0),
          stopSequences = Vector("STOP_MARKER_A", "STOP_MARKER_B")
        )
      )
      body should include("STOP_MARKER_A")
      body should include("STOP_MARKER_B")
    }
  }
}
