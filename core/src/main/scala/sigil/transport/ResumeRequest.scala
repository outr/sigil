package sigil.transport

/**
 * Where to start replay before joining the live signal stream.
 *
 *   - [[ResumeRequest.None]]: no replay, attach to live only.
 *   - [[ResumeRequest.After]]: replay events with `timestamp > cursor`
 *     (cursor in epoch-millis). Carry over the wire as
 *     `Last-Event-ID` (SSE) or as part of the resume payload
 *     (DurableSocket).
 *   - [[ResumeRequest.RecentMessages]]: replay enough history to
 *     surface the most recent N [[sigil.event.Message]] events,
 *     INCLUDING any non-Message events (ToolInvoke, ToolResults,
 *     ModeChange, TopicChange, ...) that interleave with them.
 *     Counts Messages, not events — so a chatty turn full of tool
 *     calls can't crowd Messages out of the budget.
 */
enum ResumeRequest {
  case None
  case After(cursor: Long)
  case RecentMessages(maxMessages: Int)
}
