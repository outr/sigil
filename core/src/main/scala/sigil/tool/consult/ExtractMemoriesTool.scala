package sigil.tool.consult

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedTool}

/**
 * Internal-only tool invoked by
 * [[sigil.conversation.compression.MemoryContextCompressor]]'s
 * extraction pass. Never registered on any agent's roster — the
 * compressor calls it via [[ConsultTool.invoke]] with
 * `tool_choice = required`.
 */
case object ExtractMemoriesTool extends TypedTool[ExtractMemoriesInput](
  name = ToolName("extract_memories"),
  description =
    """List the durable facts to store from a conversation excerpt. The framework will persist each fact
      |as a separate memory that future turns can look up.
      |
      |Include only facts that future agents will genuinely need:
      |  - identifiers, names, numbers, URLs explicitly stated
      |  - preferences, decisions, and commitments the user or agents made
      |  - constraints and requirements ("must be X", "cannot exceed Y")
      |
      |Do NOT include:
      |  - intermediate reasoning
      |  - small-talk, acknowledgements, retries
      |  - content that will be captured by the summary (ongoing context / narrative)
      |
      |Each fact must be self-contained: a reader seeing the fact alone, without the transcript, must
      |still be able to act on it. Quote identifiers by name. Prefer ≤ 2 sentences per fact.""".stripMargin
) {
  override protected def executeTyped(input: ExtractMemoriesInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
