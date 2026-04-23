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
    * as `role: "user"`. */
  case class User(content: String) extends ProviderMessage

  /** A message from the acting agent. May carry text content, tool
    * calls, or both. Renders as `role: "assistant"`. */
  case class Assistant(content: String, toolCalls: List[ToolCallMessage] = Nil) extends ProviderMessage

  /** A tool's result, paired to a prior assistant tool call by
    * `toolCallId`. Renders as `role: "tool"`. */
  case class ToolResult(toolCallId: String, content: String) extends ProviderMessage
}
