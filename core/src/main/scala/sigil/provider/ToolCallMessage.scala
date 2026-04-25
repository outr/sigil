package sigil.provider

/**
 * A single tool call attached to a [[ProviderMessage.Assistant]] message.
 *
 * @param id        unique id for this tool call; used to pair with a
 *                  subsequent [[ProviderMessage.ToolResult]] via its
 *                  `toolCallId`.
 * @param name      the tool's `schema.name` (wire form — string).
 * @param argsJson  the tool's input as compact JSON. Already stripped of
 *                  the `ToolInput` poly discriminator so it matches the
 *                  tool's parameter schema directly.
 */
case class ToolCallMessage(id: String, name: String, argsJson: String)
