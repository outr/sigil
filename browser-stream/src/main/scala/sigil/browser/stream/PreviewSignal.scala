package sigil.browser.stream

import fabric.rw.*
import lightdb.id.Id
import robobrowser.stream.SignalMessage
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.signal.ConversationNotice

/**
 * Server→client half of the WebRTC signaling vocabulary: an offer, an
 * ICE candidate, an error, or a bye produced by the conversation's
 * [[PreviewStreamSession.WebRtc]] session.
 *
 * A [[sigil.signal.ConversationNotice]], so it reaches only the viewers
 * subscribed to `conversationId` — a preview offer is meaningless (and
 * unwanted) in a sibling conversation's view. Transient like every
 * notice: a viewer that reconnects mid-negotiation asks for a fresh
 * stream rather than replaying a stale offer.
 *
 * `forViewer` narrows that scope to one viewer. A session started
 * through [[StreamBrowserSigil.previewStreamFor]]'s viewer-addressed
 * overload signals only its owner: the notice rides the framework's
 * targeted channel and [[StreamBrowserSigil.canSee]] drops it for
 * everyone else, so two people watching one conversation each negotiate
 * their own session instead of racing to answer a shared offer. `None`
 * is the conversation-wide broadcast the single-viewer overload
 * produces.
 *
 * The client answers with [[PreviewSignalReply]] carrying the same
 * `streamId`.
 */
final case class PreviewSignal(conversationId: Id[Conversation],
                               streamId: String,
                               message: SignalMessage,
                               forViewer: Option[ParticipantId] = None)
  extends ConversationNotice derives RW
