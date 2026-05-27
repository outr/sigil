package sigil.signal

import fabric.rw.*
import sigil.tool.model.SearchConversationHit

/**
 * Server→client [[Notice]] carrying the hits for a
 * [[RequestConversationSearch]]. Sent by the framework's default
 * [[sigil.Sigil.handleNotice]] arm; also valid as an unsolicited push
 * if an app wants to broadcast pre-computed search results.
 *
 * The `query` field echoes the original request so a UI in flight can
 * decide whether the snapshot still matches its current input
 * (debounced search boxes can drop stale snapshots when the user has
 * typed past the originating query).
 *
 * `hits` reuses the agent-tier [[SearchConversationHit]] shape so a UI
 * panel can share rendering with any agent-rendered search output —
 * eventId, timestamp, participantId, topicId, eventType, snippet.
 */
case class ConversationSearchSnapshot(query: String,
                                      hits: List[SearchConversationHit]) extends Notice derives RW
