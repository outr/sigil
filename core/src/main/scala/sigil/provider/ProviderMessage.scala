package sigil.provider

/**
 * A single message in a [[ProviderCall]]'s message log — the format-neutral
 * representation that lives between the framework's frame rendering and
 * the provider-specific wire serialization.
 *
 * Each `Provider.call` implementation translates these into its own wire
 * format (OpenAI chat-completions, Anthropic messages, etc.).
 */
sealed trait ProviderMessage

object ProviderMessage {

  /**
   * Out-of-band framework context (e.g. mode change, title change). Most
   * providers render this as a `tool` role result paired to a preceding
   * tool call when one is open, otherwise as `system`.
   */
  case class System(content: String) extends ProviderMessage

  /**
   * A message from the user (or any non-acting participant). Renders
   * as `role: "user"`. Content is a vector of blocks so text and
   * images can interleave for multimodal providers — providers that
   * don't support images drop them. Most callers construct the
   * text-only case via [[User.apply(text: String)]].
   */
  case class User(content: Vector[MessageContent]) extends ProviderMessage

  object User {

    /**
     * Shortcut for the common single-text-block case.
     */
    def apply(text: String): User = User(Vector(MessageContent.Text(text)))
  }

  /**
   * A message from the acting agent. May carry text content, tool
   * calls, or both. Renders as `role: "assistant"`.
   */
  case class Assistant(content: String, toolCalls: List[ToolCallMessage] = Nil) extends ProviderMessage

  /**
   * A tool's result, paired to a prior assistant tool call by
   * `toolCallId`. Renders as `role: "tool"`.
   */
  case class ToolResult(toolCallId: String, content: String) extends ProviderMessage

  case class Reasoning(providerItemId: String,
                       summary: List[String],
                       encryptedContent: Option[String])
    extends ProviderMessage

  /**
   * Neutral user turn injected by [[ensureUserAnchor]].
   */
  private val anchorUserMessage: ProviderMessage =
    User(Vector(MessageContent.Text("(begin conversation)")))

  /**
   * Normalize a chain for backends that require at least one user-role
   * message AND reject a conversation that ends with a content-only
   * assistant message — which they read as an assistant *prefill* to be
   * continued. Anthropic (Sonnet 4.6+ → HTTP 400 "the conversation must end
   * with a user message"), Gemini, and thinking-enabled llama.cpp chat
   * templates each enforce one or both.
   *
   * Two independent, idempotent adjustments:
   *   - no user message anywhere → prepend a neutral user turn (greet-on-join
   *     / all-assistant histories otherwise raise "no user query found");
   *   - ends with a content-only assistant message → append a neutral user
   *     turn so the model generates a fresh response instead of continuing a
   *     prefill. A trailing assistant that carries tool calls is left as-is:
   *     its tool result (a user-role message) is what legitimately follows.
   *
   * The injected turn is accidental-tail repair — a self-loop / worker-bridge
   * tail that is the agent's own prior Message lands as a trailing assistant —
   * never an intentional prefill (Sigil never prefills), so applying it
   * unconditionally is safe.
   */
  def ensureUserAnchor(messages: Vector[ProviderMessage]): Vector[ProviderMessage] = {
    val withLeadingUser =
      if (messages.exists { case _: User => true; case _ => false }) messages
      else anchorUserMessage +: messages
    val endsWithContentAssistant = withLeadingUser.lastOption.exists {
      case a: Assistant => a.toolCalls.isEmpty
      case _ => false
    }
    if (endsWithContentAssistant) withLeadingUser :+ anchorUserMessage else withLeadingUser
  }
}
