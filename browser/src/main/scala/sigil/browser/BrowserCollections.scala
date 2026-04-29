package sigil.browser

import sigil.db.SigilDB

/**
 * lightdb collection mix-in that adds the `cookieJars` store to a
 * [[SigilDB]] subclass. Apps that pull in `sigil-browser` declare
 * their concrete DB as
 * `class MyAppDB(...) extends SigilDB(...) with BrowserCollections with SecretsCollections`,
 * then refine `type DB = MyAppDB` on their Sigil instance via
 * [[BrowserSigil]].
 */
trait BrowserCollections { self: SigilDB =>
  val cookieJars: S[CookieJar, CookieJar.type] = store(CookieJar)()
}
