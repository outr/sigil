package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  BootCompletenessCheck,
  DiscoverySpec,
  Effect,
  Freshness,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolRegistrationException,
  ToolSpec
}

/** Input type deliberately NEVER registered into the polymorphic
  * `RW[ToolInput]` — the boot pass must fail naming it. */
case class UnregisteredProbeInput(value: String) extends ToolInput derives RW

/**
 * Coverage for [[BootCompletenessCheck]] — the startup pass at the end
 * of `Sigil.polymorphicRegistrations`:
 *
 *   - the framework's own registered roster passes (positive control);
 *   - a tool whose input RW was never registered fails naming the
 *     type;
 *   - a dangling `suggestedNextTools` reference fails naming both
 *     tools;
 *   - duplicate names fail;
 *   - all violations are collected into ONE exception;
 *   - a structurally different second `staticTools` read warns but
 *     does not fail.
 */
class BootCompletenessSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def stubTool(toolName: String,
                       suggested: List[ToolName] = Nil): Tool = new Tool {
    type Input = UnregisteredProbeInput
    type Output = TextToolOutput
    val io: ToolIO[UnregisteredProbeInput, TextToolOutput] = ToolIO.derived[UnregisteredProbeInput, TextToolOutput]
    val spec: ToolSpec = ToolSpec(
      name = ToolName.parse(toolName).fold(sys.error, identity),
      description = s"Boot-completeness probe tool $toolName.",
      profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
      discovery = DiscoverySpec(keywords = Set("probe", toolName), suggestedNextTools = suggested)
    )
    protected def resolve: Resolution[Input, Output] =
      Resolution.Simple((_: UnregisteredProbeInput, _: ToolContext) => Task.pure(TextToolOutput("ok")))
  }

  // Ensure the polymorphic registrations (and the framework's own boot
  // pass) have run — this doubles as the positive control: TestSigil's
  // full static roster must pass the check or this line throws.
  TestSigil.polymorphicRegistrations.sync()

  "BootCompletenessCheck" should {

    "pass for the framework's registered static roster" in {
      BootCompletenessCheck.collectViolations(TestSigil.resolvedStaticTools) shouldBe empty
    }

    "fail startup naming the type for a deliberately-unregistered input RW" in {
      val violations = BootCompletenessCheck.collectViolations(List(stubTool("unregistered_probe_tool")))
      violations should have size 1
      violations.head should include("unregistered_probe_tool")
      violations.head should include("UnregisteredProbeInput")
    }

    "fail naming both tools for a dangling suggestedNextTools reference" in {
      val dangling = stubTool("dangling_suggester", suggested = List(ToolName("suggestion_target"), ToolName("vanished_tool")))
      val violations = BootCompletenessCheck.collectViolations(List(dangling, stubTool("suggestion_target")))
        .filter(_.contains("suggestedNextTools"))
      violations should have size 1
      violations.head should include("dangling_suggester")
      violations.head should include("vanished_tool")
      violations.head should not include "suggestion_target"
    }

    "resolve suggestedNextTools references against the full registered set" in {
      val suggester = stubTool("resolving_suggester", suggested = List(ToolName("suggestion_target")))
      val target = stubTool("suggestion_target")
      BootCompletenessCheck.collectViolations(List(suggester, target))
        .filter(_.contains("suggestedNextTools")) shouldBe empty
    }

    "fail on duplicate tool names" in {
      val violations = BootCompletenessCheck.collectViolations(List(stubTool("twin_tool"), stubTool("twin_tool")))
      violations.filter(_.contains("duplicate")) should have size 1
      violations.filter(_.contains("duplicate")).head should include("twin_tool")
    }

    "collect every violation into one ToolRegistrationException" in {
      val roster = List(
        stubTool("broken_one", suggested = List(ToolName("vanished_tool"))),
        stubTool("broken_two")
      )
      val ex = intercept[ToolRegistrationException] {
        BootCompletenessCheck.run(roster, roster)
      }
      // One dangling suggestion + two unregistered-input probes.
      ex.violations.size shouldBe 3
      ex.getMessage should include("vanished_tool")
      ex.getMessage should include("UnregisteredProbeInput")
    }

    "warn without failing when the second staticTools read is structurally different" in {
      val stable = TestSigil.resolvedStaticTools
      noException should be thrownBy BootCompletenessCheck.run(stable, stable.reverse)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
