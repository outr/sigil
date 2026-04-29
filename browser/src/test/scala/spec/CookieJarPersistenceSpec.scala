package spec

import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.{GlobalSpace, SpaceId}
import sigil.browser.{BrowserCookie, CookieJar}
import sigil.participant.ParticipantId

/**
 * Coverage for [[sigil.browser.BrowserSigil.saveCookies]] /
 * [[sigil.browser.BrowserSigil.loadCookies]] — encrypted at rest via
 * `secretStore`, transparent typed round-trip on read.
 */
class CookieJarPersistenceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  case object SpecUser extends ParticipantId {
    override val value: String = "spec-user"
  }
  ParticipantId.register(RW.static(SpecUser))

  TestBrowserSigil.initFor(getClass.getSimpleName)

  "BrowserSigil.saveCookies / loadCookies" should {

    "round-trip a cookie list through the encrypted jar" in {
      val jarId = CookieJar.id()
      val cookies = List(
        BrowserCookie("example.com", "session", "abc123", path = "/", secure = true),
        BrowserCookie("example.com", "csrf", "xyz789", path = "/", httpOnly = true)
      )
      for {
        _      <- TestBrowserSigil.saveCookies(jarId, GlobalSpace, cookies,
                    metadata = Map("label" -> "example.com"))
        loaded <- TestBrowserSigil.loadCookies(jarId)
      } yield {
        loaded should have size 2
        loaded.map(_.name).toSet shouldBe Set("session", "csrf")
        loaded.find(_.name == "session").get.value shouldBe "abc123"
        loaded.find(_.name == "csrf").get.httpOnly shouldBe true
      }
    }

    "return Nil for a missing jar id" in {
      TestBrowserSigil.loadCookies(CookieJar.id("does-not-exist")).map { loaded =>
        loaded shouldBe empty
      }
    }

    "overwrite the cookie list on a re-save" in {
      val jarId = CookieJar.id()
      for {
        _       <- TestBrowserSigil.saveCookies(jarId, GlobalSpace,
                     List(BrowserCookie("a.com", "k", "v1")))
        first   <- TestBrowserSigil.loadCookies(jarId)
        _       <- TestBrowserSigil.saveCookies(jarId, GlobalSpace,
                     List(BrowserCookie("a.com", "k", "v2")))
        second  <- TestBrowserSigil.loadCookies(jarId)
      } yield {
        first.map(_.value) shouldBe List("v1")
        second.map(_.value) shouldBe List("v2")
      }
    }

    "persist the metadata + space on the record" in {
      val jarId = CookieJar.id()
      for {
        _      <- TestBrowserSigil.saveCookies(jarId, GlobalSpace,
                    List(BrowserCookie("x.com", "k", "v")),
                    metadata = Map("user" -> "alice", "label" -> "x.com / alice"))
        record <- TestBrowserSigil.withDB(_.cookieJars.transaction(_.get(jarId)))
      } yield {
        record shouldBe defined
        record.get.space shouldBe GlobalSpace
        record.get.metadata("user") shouldBe "alice"
        record.get.metadata("label") shouldBe "x.com / alice"
      }
    }
  }
}
