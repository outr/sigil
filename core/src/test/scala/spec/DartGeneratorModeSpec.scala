package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.ActiveSkillSlot
import sigil.provider.{Mode, ToolPolicy}

/**
 * Coverage for the Mode-codegen interaction arc that started with #215 (Mode
 * wire RW uses `Mode.name` under the JSON key `"name"`):
 *
 *  - #217 — spice PascalCases hyphenated discriminators; Sigil registers a
 *    `polyDiscriminatorKeys` override so the parent dispatcher / subtype
 *    `toJson` use `"name"`, not `"type"`.
 *  - #220 — Mode's Definition carries the subtype's Scala FQN as `className`
 *    (instead of the wire discriminator value) so the Dart class name follows
 *    the Scala class (`TestHyphenatedMode`) and doesn't shadow same-named
 *    entity classes. Wire discriminator value is preserved verbatim because
 *    it's sourced from the poly's VectorMap key, not from `className`.
 */
class DartGeneratorModeSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  // Register two test-only Modes:
  //   - hyphenated name → exercises the kebab-case → PascalCase path (#217)
  //   - single-word lowercase name → exercises the unconditional PascalCase
  //     pass (#218); without it the emitted Dart base class's `static const
  //     Mode singleword = singleword();` would fail to parse in Dart's
  //     const context.
  // RW.static-shaped per Mode.register's contract.
  Mode.register(RW.static[Mode](TestHyphenatedMode))
  Mode.register(RW.static[Mode](TestSingleWordMode))

  private val ModeWire = "Signal" -> summon[RW[Mode]].definition

  private def generate(): List[spice.openapi.generator.SourceFile] =
    spice.openapi.generator.dart.DurableSocketDartGenerator(
      spice.openapi.generator.dart.DurableSocketDartConfig(
        serviceName           = "Test",
        wireType              = ModeWire,
        polyDiscriminatorKeys = Map(classOf[Mode].getName -> "name")
      )
    ).generate()

  "Dart codegen for Mode (sigil bug #217)" should {

    "use the Scala class FQN to derive the Dart class name (sigil bug #220)" in {
      // Since #220 the Dart class name follows the Mode's Scala class
      // (`TestHyphenatedMode`) instead of being derived from the wire
      // discriminator. That avoids collisions with same-named entity classes
      // (`Conversation` Mode would shadow the `Conversation` entity record on
      // the Dart side).
      val files = generate()
      val names = files.map(_.fileName).toSet
      withClue(s"file names: ${names.toList.sorted.mkString(", ")}\n") {
        names should contain("test_hyphenated_mode.dart")
        names should not contain "test_with_hyphens.dart"
      }
      val classDecl = files.find(_.fileName == "test_hyphenated_mode.dart").map(_.source).getOrElse("")
      classDecl should include("class TestHyphenatedMode")
    }

    "emit `'name'` as the JSON discriminator key (not the default `'type'`) on the subtype's toJson" in {
      val files = generate()
      val classDecl = files.find(_.fileName == "test_hyphenated_mode.dart").map(_.source).getOrElse("")
      // The actual wire value stays the literal kebab-case discriminator —
      // round-trip through Sigil's Mode RW pulls the same string back out
      // regardless of what the Dart class is called.
      classDecl should include("'name': 'test-with-hyphens'")
      classDecl should not include "'type': 'test-with-hyphens'"
    }

    "emit `'name'` as the JSON discriminator key on the parent's fromJson dispatcher" in {
      val files = generate()
      // Parent abstract class lives in its own file under the sigil/provider/
      // package — whichever file declares the `abstract class Signal` parent
      // is the one carrying the fromJson dispatcher (wireType is named
      // "Signal" for the generator's purposes; the actual Definition is
      // Mode's poly).
      val parentFile = files.find(_.source.contains("abstract class Signal"))
      withClue(s"file names: ${files.map(_.fileName).sorted.mkString(", ")}\n") {
        parentFile shouldBe defined
      }
      val parentSource = parentFile.map(_.source).getOrElse("")
      withClue(s"parent source:\n$parentSource\n") {
        parentSource should include("json['name']")
        parentSource should not include "json['type'] as String"
      }
    }

    // Sigil bug #218 → #220 — single-word Mode names route through the same
    // Scala-FQN-derived class naming as hyphenated ones. The test mode
    // `TestSingleWordMode` (wire name `"singleword"`) emits as
    // `class TestSingleWordMode` (not `class Singleword`, which would risk
    // shadowing any entity class named `Singleword`).
    "use the Scala class FQN for single-word Mode discriminators too" in {
      val files = generate()
      val classFile = files.find(_.fileName == "test_single_word_mode.dart")
      withClue(s"file names: ${files.map(_.fileName).sorted.mkString(", ")}\n") {
        classFile shouldBe defined
      }
      val source = classFile.map(_.source).getOrElse("")
      source should include("class TestSingleWordMode")
      // Wire discriminator value is preserved verbatim.
      source should include("'name': 'singleword'")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.sync()
  }
}

case object TestSingleWordMode extends Mode {
  override val name: String = "singleword"
  override val description: String = "Synthetic mode for the #218 spec — exercises single-word lowercase discriminators."
  override val skill: Option[ActiveSkillSlot] = None
  override val tools: ToolPolicy = ToolPolicy.Standard
}

case object TestHyphenatedMode extends Mode {
  override val name: String = "test-with-hyphens"
  override val description: String = "Synthetic mode for the #217 spec — exercises hyphenated wire discriminators."
  override val skill: Option[ActiveSkillSlot] = None
  override val tools: ToolPolicy = ToolPolicy.Standard
}
