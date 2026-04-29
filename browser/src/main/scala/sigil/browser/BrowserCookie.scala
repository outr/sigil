package sigil.browser

import fabric.rw.*

/**
 * Single browser cookie. Mirrors the subset of CDP's `Network.Cookie`
 * shape that's persistable / reload-able — RoboBrowser's
 * [[robobrowser.event.Cookie]] carries CDP-runtime fields like
 * `session`, `sourceScheme`, `sourcePort` that aren't stable across
 * reloads, so we project to a leaner shape here.
 *
 * `expiresEpochMs` is the absolute wall-clock instant (millis since
 * 1970) the cookie expires. `None` means session-cookie semantics —
 * cleared at end-of-session, useful for re-creating short-lived
 * tokens but not relevant to long-term resume.
 */
case class BrowserCookie(domain: String,
                         name: String,
                         value: String,
                         path: String = "/",
                         secure: Boolean = true,
                         httpOnly: Boolean = false,
                         sameSite: Option[String] = None,
                         expiresEpochMs: Option[Long] = None) derives RW
