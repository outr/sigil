package sigil.tool.model

import fabric.rw.*

/** Per-hit shape inside [[SearchConversationOutput]]. `eventType`
  * is the simple-name of the matching Event subclass (`Message`,
  * `TopicChange`, etc.). `snippet` is a 280-char preview — agents
  * iterate the list and call `lookup` for full bodies if needed. */
case class SearchConversationHit(eventId: String,
                                 timestamp: Long,
                                 participantId: String,
                                 topicId: String,
                                 eventType: String,
                                 snippet: String) derives RW

/** Typed result for [[sigil.tool.util.SearchConversationTool]]. */
case class SearchConversationOutput(query: String,
                                    hits: List[SearchConversationHit],
                                    count: Int) derives RW
