package spec

import fabric.*
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ConversationMode, Mode}

/**
 * Coverage for [[Mode]] wire serialization. The polymorphic discriminator
 * on the wire is [[Mode.name]] — the value persisted in `ModeChange`
 * events and resolved by `Sigil.modeByName` for the `change_mode` tool —
 * not the Scala class name. Clients reading the wire (`name`) must see
 * exactly what was persisted; if the discriminator drifted to the class
 * name, downstream UIs render the wrong mode chip and the model parrots
 * the wrong name back when the wire payload round-trips through it.
 *
 * Also pins the name-conflict semantics: registering two case objects
 * that both claim `name = "alpha"` is last-write-wins (matches the
 * underlying `fabric.rw.PolyType.register` shape — that registry is an
 * append-only `var` walked top-to-bottom).
 */
class ModeSerializationSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  "Mode wire RW" should {
    "use the Mode.name field as the discriminator on serialize" in {
      val rw = summon[RW[Mode]]
      val json = rw.read(TestModeAlpha)
      json("name").asString shouldBe "alpha"
    }

    "not leak the Scala class name in the serialized wire body" in {
      val rw = summon[RW[Mode]]
      val json = rw.read(TestModeAlpha)
      json.asObj.value.keySet should not contain "type"
      json.toString should not include "TestModeAlpha"
    }

    "round-trip a registered mode by name" in {
      val rw = summon[RW[Mode]]
      rw.write(rw.read(TestModeAlpha)) shouldBe TestModeAlpha
    }

    "round-trip ConversationMode by its 'conversation' name" in {
      val rw = summon[RW[Mode]]
      val json = rw.read(ConversationMode)
      json("name").asString shouldBe "conversation"
      rw.write(json) shouldBe ConversationMode
    }

    "resolve via Sigil.modeByName using the wire discriminator" in {
      TestSigil.modeByName(summon[RW[Mode]].read(TestModeAlpha)("name").asString) shouldBe Some(TestModeAlpha)
    }
  }

  "Mode registration with a duplicate name" should {
    "be last-write-wins (matches PolyType.register append-only shape)" in {
      // Both case objects claim the same `name = "alpha"`. The later
      // registration wins because the underlying registry is an
      // append-only list walked top-to-bottom on lookup. This matches
      // existing fabric `PolyType` semantics — there is no hard conflict
      // check at registration time.
      Mode.register(RW.static(TestModeAlphaDuplicate))
      val rw = summon[RW[Mode]]
      val resolved = rw.write(rw.read(TestModeAlpha))
      // The later-registered duplicate wins on the lookup map, so the
      // resolved instance is `TestModeAlphaDuplicate`, not the original.
      resolved shouldBe TestModeAlphaDuplicate
      // Restore: re-register the original so subsequent specs aren't
      // poisoned by the duplicate sticking around.
      Mode.register(RW.static(TestModeAlpha))
      summon[RW[Mode]].write(summon[RW[Mode]].read(TestModeAlpha)) shouldBe TestModeAlpha
    }
  }
}

/**
 * Test-only mode used by [[ModeSerializationSpec]]. Registered via
 * [[TestSigil.modes]].
 */
case object TestModeAlpha extends Mode {
  override val name: String = "alpha"
  override val description: String = "Test mode for serialization coverage."
}

/**
 * Conflict-duplicate of [[TestModeAlpha]] — same `name`, different
 * Scala class. Registered ad-hoc inside the duplicate-name test to
 * exercise the last-write-wins behaviour without polluting the
 * default [[TestSigil.modes]] roster.
 */
case object TestModeAlphaDuplicate extends Mode {
  override val name: String = "alpha"
  override val description: String = "Conflicting duplicate of TestModeAlpha."
}
