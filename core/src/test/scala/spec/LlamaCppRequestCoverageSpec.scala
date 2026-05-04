package spec

import fabric.io.JsonParser
import lightdb.id.Id
import sigil.conversation.{ContextFrame, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.Event
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

    // Regression for BUGS.md Sigil#7 — when the conversation has only
    // System frames (typical greet-on-join: TopicChange / ModeChange
    // history with no user content), the wire payload must not emit
    // multiple system-role entries. Qwen3.5's chat template walks the
    // messages array and raises "System message must be at the beginning"
    // on the first non-leading System entry. The provider must collapse
    // any leading-System tail of `input.messages` into the framework's
    // single `input.system` so the rendered tail is user/assistant only.
    "collapse leading system messages into a single system entry (greet-on-join with system-only history)" in {
      val systemOnlyView = emptyView.copy(frames = Vector(
        ContextFrame.System(
          content = "Topic switched to: Greeting",
          sourceEventId = Id[Event]("sys-1")
        ),
        ContextFrame.System(
          content = "Mode changed to conversation.",
          sourceEventId = Id[Event]("sys-2")
        )
      ))
      val body = bodyOf(TurnInput(systemOnlyView))
      val messages: Vector[fabric.Json] = JsonParser(body).get("messages").map(_.asVector).getOrElse(Vector.empty)
      val roles: Vector[String] = messages.flatMap(_.get("role").map(_.asString))
      withClue(s"roles: ${roles.mkString(",")}") {
        roles.count(_ == "system") shouldBe 1
        roles.count(_ == "user")   shouldBe 1
        roles.head shouldBe "system"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
