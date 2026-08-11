package sigil.browser.stream

import fabric.rw.*
import lightdb.id.Id
import robobrowser.stream.SignalMessage
import sigil.conversation.Conversation
import sigil.signal.ConversationNotice

/**
 * Client→server half of the WebRTC signaling vocabulary — the viewer's
 * answer, its trickled ICE candidates, or a `bye` ending the preview.
 * Arrives over the same inbound-notice path as every other client
 * request (`SessionBridge` → `Sigil.handleNotice`) and is routed into
 * the addressed session by [[StreamBrowserSigil]].
 *
 * A `streamId` naming no live session is warned about and dropped: a
 * viewer whose session was reaped mid-negotiation must ask for a new
 * stream, not resurrect a dead one.
 */
final case class PreviewSignalReply(conversationId: Id[Conversation],
                                    streamId: String,
                                    message: SignalMessage) extends ConversationNotice derives RW
