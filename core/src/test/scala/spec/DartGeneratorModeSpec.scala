package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.ActiveSkillSlot
import sigil.provider.{Mode, ToolPolicy}

/**
 * Sigil bug #217 — after #215 changed Mode wire RW to use `Mode.name` as the
 * discriminator (under JSON key `"name"`, not `"type"`), the Dart codegen
 * downstream consumer wasn't updated to match. Two surfaces broke:
 *
 *  1. Kebab-case mode names (`"workflow-builder"`) flowed through the codegen
 *     as Dart class names, producing `class workflow-builder` — invalid
 *     identifier.
 *  2. The emitted `toJson()` body / parent `fromJson` dispatcher hardcoded
 *     the JSON key `'type'`, mismatching the actual wire shape Sigil's RW
 *     emits (`'name'`).
 *
 * The fix lands in spice (`DartNames` PascalCases hyphenated discriminators;
 * `DurableSocketDartConfig.polyDiscriminatorKeys` lets a consumer override the
 * JSON key per polymorphic parent). Sigil registers the override for `Mode`.
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
        serviceName = "Test",
        wireType = ModeWire,
        polyDiscriminatorKeys = Map(classOf[Mode].getName -> "name")
      )
    ).generate()

  "Dart codegen for Mode (sigil bug #217)" should {

    "PascalCase the hyphenated discriminator into a valid Dart class name" in {
      val files = generate()
      val names = files.map(_.fileName).toSet
      withClue(s"file names: ${names.toList.sorted.mkString(", ")}\n") {
        names should contain("test_with_hyphens.dart")
        names.exists(_.contains("test-with-hyphens")) shouldBe false
      }
      val classDecl = files.find(_.fileName == "test_with_hyphens.dart").map(_.source).getOrElse("")
      classDecl should include("class TestWithHyphens")
      classDecl should not include "class test-with-hyphens"
    }

    "emit `'name'` as the JSON discriminator key (not the default `'type'`) on the subtype's toJson" in {
      val files = generate()
      val classDecl = files.find(_.fileName == "test_with_hyphens.dart").map(_.source).getOrElse("")
      // The actual wire value stays the literal kebab-case discriminator —
      // round-trip through Sigil's Mode RW pulls the same string back out.
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

    // Sigil bug #218 — single-word lowercase discriminator names (e.g. `coding`,
    // `conversation`) need their first letter capitalised. The parser accepts
    // `class coding` at the declaration site, but the parent's
    // `static const Mode singleword = singleword();` reference becomes a
    // method-invocation parse error in Dart's const context.
    "PascalCase a single-word lowercase discriminator (no separator)" in {
      val files = generate()
      val classFile = files.find(_.fileName == "singleword.dart")
      withClue(s"file names: ${files.map(_.fileName).sorted.mkString(", ")}\n") {
        classFile shouldBe defined
      }
      val source = classFile.map(_.source).getOrElse("")
      source should include("class Singleword")
      source should not include "class singleword extends"
    }

    // Sigil bug #219 — single-word discriminators must land at the top of the
    // model tree (no subdirectory), matching the layout the hyphenated path
    // uses. Routing them into `model/<name>/<name>.dart` desyncs the
    // polymorphic base's `const` references and produces a mixed-shape output
    // that the Dart const-context parser rejects.
    "emit single-word Mode subtypes flat under model/ (not nested in a same-name subdirectory)" in {
      val files = generate()
      val singlewordFile = files.find(_.fileName == "singleword.dart")
      singlewordFile shouldBe defined
      val path = singlewordFile.map(_.path).getOrElse("")
      withClue(s"single-word path: $path\n") {
        path should not endWith "/singleword"
        path should not include "/model/singleword/"
      }
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
