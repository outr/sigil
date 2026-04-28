package spec

import lightdb.id.Id
import sigil.conversation.{ConversationView, TurnInput}
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

    // Regression for BUGS.md Sigil#4 — Qwen3.5's chat template raises
    // "No user query found in messages" when the messages array has
    // no user-role entry. The greet-on-join turn legitimately has no
    // user content (the agent is introducing itself before any user
    // input). LlamaCpp must inject a synthetic placeholder user
    // message so the chat-template's required-user-anchor invariant
    // is satisfied.
    "inject a synthetic user-role message when the conversation has no user frames (greet-on-join shape)" in {
      val frameless: ConversationView = emptyView.copy(frames = Vector.empty)
      val body = bodyOf(TurnInput(frameless))
      // The body's `messages` array must contain at least one
      // `"role":"user"` entry — without it, Qwen3.5's chat template
      // raises and the request returns HTTP 500.
      body should include("\"role\":\"user\"")
    }
  }
}
