package sigil.db

import fabric.rw.*

/**
 * Capability details as reported by the top (primary) serving provider.
 * These may be narrower than what alternative providers of the same model offer.
 *
 * @param contextLength       Maximum combined input+output context window, in tokens.
 * @param maxCompletionTokens Maximum output tokens produced in a single call.
 * @param isModerated         Whether the top provider applies content moderation to requests.
 */
case class ModelTopProvider(contextLength: Option[Long], maxCompletionTokens: Option[Long], isModerated: Boolean) derives RW
