package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.ToolPolicy

/**
 * Sigil bug #268 — `static const` shortcut accessors for empty case-objects
 * must use the leaf case name as a Dart identifier, never the dotted JSON
 * discriminator (`ToolPolicy.Standard` → `standard`, not `toolPolicy.Standard`
 * which is invalid Dart syntax).
 *
 * Triggered when fabric 1.29 switched polymorphic dispatch keys to the
 * class-chain form (`ParentEnum.CaseName`). The wire `"type"` field VALUES
 * embedded in `fromJson` dispatchers correctly carry the chain; the
 * accessor identifier names must strip back down to the leaf.
 */
class DartGeneratorStaticAccessorSpec extends AnyWordSpec with Matchers {

  private val ToolPolicyWire: (String, fabric.define.Definition) =
    "ToolPolicy" -> summon[RW[ToolPolicy]].definition

  private def generate(): List[spice.openapi.generator.SourceFile] =
    spice.openapi.generator.dart.DurableSocketDartGenerator(
      spice.openapi.generator.dart.DurableSocketDartConfig(
        serviceName = "Test",
        wireType = ToolPolicyWire
      )
    ).generate()

  "Dart codegen for ToolPolicy" should {

    "emit valid Dart identifiers (no dots) for static-const accessors of empty cases" in {
      val files = generate()
      val parent = files.find(_.fileName == "tool_policy.dart")
      withClue(s"file names: ${files.map(_.fileName).sorted.mkString(", ")}\n") {
        parent shouldBe defined
      }
      val source = parent.map(_.source).getOrElse("")

      // Standard, None, and PureDiscovery are parameterless cases — each
      // gets a `static const ToolPolicy <ident> = ToolPolicy<Case>();`
      // shortcut. The identifier must be the leaf-name camelCased.
      source should include("static const ToolPolicy standard = ToolPolicyStandard();")
      source should include("static const ToolPolicy pureDiscovery = ToolPolicyPureDiscovery();")

      // No accessor identifier may contain a `.` — that was the #268 bug.
      val dottedAccessor = """static const \w+ [a-zA-Z]+\.[a-zA-Z]""".r
      withClue(s"found a dotted static-const accessor in tool_policy.dart:\n$source\n") {
        dottedAccessor.findFirstIn(source) shouldBe scala.None
      }
    }

    "keep the class-chain form in the JSON discriminator inside fromJson" in {
      val files = generate()
      val source = files.find(_.fileName == "tool_policy.dart").map(_.source).getOrElse("")
      // The wire `"type"` value is the class-chain string — fabric 1.29
      // emits the parent prefix so leaf collisions across sibling enums
      // can't drift onto each other's dispatch slots.
      source should include("'ToolPolicy.Standard'")
      source should include("'ToolPolicy.PureDiscovery'")
    }
  }
}
