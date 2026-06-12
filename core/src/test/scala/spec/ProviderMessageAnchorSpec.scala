package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{MessageContent, ProviderMessage, ToolCallMessage}

/**
 * Sigil #396 — pure coverage for [[ProviderMessage.ensureUserAnchor]], the
 * shared chain-normalization that keeps a trailing-assistant "prefill" or a
 * userless history from reaching a backend that rejects either (Anthropic
 * Sonnet 4.6+, Gemini, thinking-enabled llama.cpp).
 */
class ProviderMessageAnchorSpec extends AnyWordSpec with Matchers {
  import ProviderMessage.*

  private def user(t: String)      = User(Vector(MessageContent.Text(t)))
  private def assistant(t: String) = Assistant(t)
  private def assistantWithTool    = Assistant("", List(ToolCallMessage("c1", "do", "{}")))

  "ensureUserAnchor" should {
    "leave a chain that already ends with a user message untouched" in {
      val in = Vector(user("hi"), assistant("reply"), user("more"))
      ensureUserAnchor(in) shouldBe in
    }

    "append a user anchor when the chain ends with a content-only assistant" in {
      val out = ensureUserAnchor(Vector(user("do the work"), assistant("here is my reply")))
      out.last shouldBe a[User]
      out.size shouldBe 3
    }

    "leave a trailing assistant that carries tool calls untouched (its tool result follows)" in {
      val in = Vector(user("go"), assistantWithTool)
      ensureUserAnchor(in) shouldBe in
    }

    "prepend a user anchor when the chain has no user message at all" in {
      val out = ensureUserAnchor(Vector(System("[system] boot")))
      out.head shouldBe a[User]
      out.exists { case _: User => true; case _ => false } shouldBe true
    }

    "both prepend AND append when there is no user and the tail is a content assistant" in {
      val out = ensureUserAnchor(Vector(assistant("solo agent turn")))
      out.head shouldBe a[User]
      out.last shouldBe a[User]
      out.size shouldBe 3
    }

    "anchor an empty chain to a single user message" in {
      val out = ensureUserAnchor(Vector.empty)
      out shouldBe Vector(user("(begin conversation)"))
    }
  }
}
