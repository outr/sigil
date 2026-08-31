package spec

import fabric.rw.*
import fabric.{Str, arr, num, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.diagnostics.{ProfileSection, RequestProfile}

/**
 * `ProfileSection` is the key type of `RequestProfile.sections`, which
 * rides the `WireRequestProfile` notice out to every client that draws a
 * context-utilisation gauge. Its serialized form is therefore a wire
 * contract, and the shapes pinned here are the ones already on that
 * wire: each case a `ProfileSection.<Name>` string, and — because the
 * definition is polymorphic rather than a bare string — a keyed map as
 * fabric's array of `{key, value}` pairs.
 */
class ProfileSectionWireFormatSpec extends AnyWordSpec with Matchers {

  "A ProfileSection" should {
    "serialize as its class-chain name" in {
      ProfileSection.Memories.json shouldBe Str("ProfileSection.Memories")
      ProfileSection.ToolRoster.json shouldBe Str("ProfileSection.ToolRoster")
    }

    "round-trip every closed case" in
      ProfileSection.values.foreach { section =>
        section.json.as[ProfileSection] shouldBe section
      }

    "still read the leaf-form name earlier records carry" in {
      str("Memories").as[ProfileSection] shouldBe ProfileSection.Memories
    }
  }

  "A ProfileSection-keyed map" should {
    "serialize as fabric's array of key/value pairs" in {
      Map(ProfileSection.Memories -> 5).json shouldBe
        arr(obj("key" -> str("ProfileSection.Memories"), "value" -> num(5)))
    }

    "round-trip inside a RequestProfile" in {
      val profile = RequestProfile(
        total = 12,
        sections = Map(ProfileSection.Memories -> 5, ProfileSection.Frames -> 7),
        frames = Vector.empty
      )
      profile.json.as[RequestProfile] shouldBe profile
    }
  }
}
