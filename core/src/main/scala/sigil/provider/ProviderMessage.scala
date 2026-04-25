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

  /** Out-of-band framework context (e.g. mode change, title change). Most
    * providers render this as a `tool` role result paired to a preceding
    * tool call when one is open, otherwise as `system`. */
  case class System(content: String) extends ProviderMessage

  /** A message from the user (or any non-acting participant). Renders
    * as `role: "user"`. Content is a vector of blocks so text and
    * images can interleave for multimodal providers — providers that
    * don't support images drop them. Most callers construct the
    * text-only case via [[User.apply(text: String)]]. */
  case class User(content: Vector[MessageContent]) extends ProviderMessage

  object User {
    /** Shortcut for the common single-text-block case. */
    def apply(text: String): User = User(Vector(MessageContent.Text(text)))
  }

  /** A message from the acting agent. May carry text content, tool
    * calls, or both. Renders as `role: "assistant"`. */
  case class Assistant(content: String, toolCalls: List[ToolCallMessage] = Nil) extends ProviderMessage

  /** A tool's result, paired to a prior assistant tool call by
    * `toolCallId`. Renders as `role: "tool"`. */
  case class ToolResult(toolCallId: String, content: String) extends ProviderMessage
}
