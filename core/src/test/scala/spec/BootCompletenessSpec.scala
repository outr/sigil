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

/**
 * Input type deliberately NEVER registered into the polymorphic
 * `RW[ToolInput]` — the boot pass must fail naming it.
 */
case class UnregisteredProbeInput(value: String) extends ToolInput derives RW

/**
 * A field-carrying SpaceId — the shape every multi-tenant downstream
 * app registers, and the shape that makes a required SpaceId input
 * union unfillable.
 */
case class Bug437FieldSpace(tenantId: String) extends sigil.SpaceId {
  override val value: String = s"tenant-$tenantId"
}
object Bug437FieldSpace {
  implicit val rw: RW[Bug437FieldSpace] = RW.gen
}

/**
 * Deliberately violates the ergonomics rule once a field-carrying
 * SpaceId variant is registered.
 */
case class RequiredSpaceProbeInput(target: sigil.SpaceId) extends ToolInput derives RW

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
 *   - two input types sharing a SIMPLE class name fail naming both
 *     FQCNs (fabric dispatches by lowercased simple name while
 *     registration dedupes by FQCN, so one silently shadows the other);
 *   - an accessor that diverges from the tool's spec fails;
 *   - all violations are collected into ONE exception.
 *
 * Also pins the fabric error text the pass hangs on: the
 * unregistered-polytype probe classifies by matching "Type not found",
 * so a fabric rewording would silently disable the whole check.
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

  private val collideAIO: ToolIO[spec.collidea.CollidingProbeInput, TextToolOutput] =
    ToolIO.derived[spec.collidea.CollidingProbeInput, TextToolOutput]
  private val collideBIO: ToolIO[spec.collideb.CollidingProbeInput, TextToolOutput] =
    ToolIO.derived[spec.collideb.CollidingProbeInput, TextToolOutput]

  private def collidingTool[I <: ToolInput](toolName: String, toolIO: ToolIO[I, TextToolOutput]): Tool = new Tool {
    type Input = I
    type Output = TextToolOutput
    val io: ToolIO[I, TextToolOutput] = toolIO
    val spec: ToolSpec = ToolSpec(
      name = ToolName.parse(toolName).fold(sys.error, identity),
      description = s"Simple-name collision probe $toolName.",
      profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
      discovery = DiscoverySpec(keywords = Set("probe", toolName))
    )
    protected def resolve: Resolution[Input, Output] =
      Resolution.Simple((_: I, _: ToolContext) => Task.pure(TextToolOutput("ok")))
  }

  // Ensure the polymorphic registrations (and the framework's own boot
  // pass) have run — this doubles as the positive control: TestSigil's
  // full static roster must pass the check or this line throws.
  TestSigil.polymorphicRegistrations.sync()

  "BootCompletenessCheck" should {

    "pass for the framework's registered static roster" in {
      BootCompletenessCheck.collectViolations(TestSigil.resolvedStaticTools) shouldBe empty
    }

    "pass the shipped roster with a field-carrying SpaceId registered (the downstream-app shape)" in {
      // A multi-tenant app's spaces carry fields (tenant/user ids). No
      // shipped tool may take a required SpaceId — a field-carrying
      // variant would make that union unfillable and abort every
      // consumer's boot.
      sigil.SpaceId.register(Bug437FieldSpace.rw)
      BootCompletenessCheck.collectViolations(TestSigil.resolvedStaticTools) shouldBe empty
    }

    "still flag a required poly-union input once a field-carrying variant is registered" in {
      sigil.SpaceId.register(Bug437FieldSpace.rw)
      val offender: Tool = new Tool {
        type Input = RequiredSpaceProbeInput
        type Output = TextToolOutput
        val io: ToolIO[RequiredSpaceProbeInput, TextToolOutput] =
          ToolIO.derived[RequiredSpaceProbeInput, TextToolOutput]
        val spec: ToolSpec = ToolSpec(
          name = ToolName.parse("required_space_probe").fold(sys.error, identity),
          description = "Probe with a required SpaceId union input.",
          profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
          discovery = DiscoverySpec(keywords = Set("probe", "space"))
        )
        protected def resolve: Resolution[Input, Output] =
          Resolution.Simple((_: RequiredSpaceProbeInput, _: sigil.tool.ToolContext) => Task.pure(TextToolOutput("ok")))
      }
      val violations = BootCompletenessCheck.collectViolations(List(offender))
        .filter(_.contains("model-fillable"))
      violations should not be empty
      violations.head should include("required_space_probe")
    }

    "pass for a registered output type whose refined fields reject synthesized probe values" in {
      // ImageToolOutput.url is a URL — the definition-driven synthesizer
      // cannot fabricate a parseable value, so the probe must fall back
      // to discriminator dispatch rather than reporting a registration
      // violation.
      val screenshotLike: Tool = new Tool {
        type Input = sigil.tool.JsonInput
        type Output = sigil.tool.ImageToolOutput
        val io: ToolIO[sigil.tool.JsonInput, sigil.tool.ImageToolOutput] =
          ToolIO.dynamicAs[sigil.tool.ImageToolOutput](summon[fabric.rw.RW[UnregisteredProbeInput]].definition)
        val spec: ToolSpec = ToolSpec(
          name = ToolName.parse("refined_output_probe").fold(sys.error, identity),
          description = "Probe tool with a URL-typed output field.",
          profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
          discovery = DiscoverySpec(keywords = Set("probe", "refined"))
        )
        protected def resolve: Resolution[Input, Output] = {
          import spice.net.*
          Resolution.Simple((_: sigil.tool.JsonInput, _: ToolContext) =>
            Task.pure(
              sigil.tool.ImageToolOutput(url = url"https://example.invalid/probe.png")))
        }
      }
      BootCompletenessCheck.collectViolations(List(screenshotLike)) shouldBe empty
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
        BootCompletenessCheck.run(roster)
      }
      // One dangling suggestion + two unregistered-input probes.
      ex.violations.size shouldBe 3
      ex.getMessage should include("vanished_tool")
      ex.getMessage should include("UnregisteredProbeInput")
    }

    "fail naming both FQCNs when two input types share a simple class name" in {
      val violations = BootCompletenessCheck
        .collectViolations(List(collidingTool("collide_a", collideAIO), collidingTool("collide_b", collideBIO)))
        .filter(_.contains("simple name"))
      violations should have size 1
      violations.head should include("spec.collidea.CollidingProbeInput")
      violations.head should include("spec.collideb.CollidingProbeInput")
    }

    "not report a collision when the same input type is reused across tools" in {
      BootCompletenessCheck
        .collectViolations(List(stubTool("reuse_one"), stubTool("reuse_two")))
        .filter(_.contains("simple name")) shouldBe empty
    }

    "fail when a tool's accessor diverges from its spec" in {
      val divergent: Tool = new Tool {
        type Input = UnregisteredProbeInput
        type Output = TextToolOutput
        val io: ToolIO[UnregisteredProbeInput, TextToolOutput] = ToolIO.derived[UnregisteredProbeInput, TextToolOutput]
        val spec: ToolSpec = ToolSpec(
          name = ToolName("spec_name_probe"),
          description = "Probe whose `name` accessor lies about its spec.",
          profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
          discovery = DiscoverySpec(keywords = Set("probe"))
        )
        override val name: ToolName = ToolName("accessor_name_probe")
        protected def resolve: Resolution[Input, Output] =
          Resolution.Simple((_: UnregisteredProbeInput, _: ToolContext) => Task.pure(TextToolOutput("ok")))
      }
      val violations = BootCompletenessCheck.collectViolations(List(divergent)).filter(_.contains("overrides `name`"))
      violations should have size 1
      violations.head should include("spec_name_probe")
    }
  }

  "fabric's unregistered-polytype error" should {
    "still report 'Type not found'" in {
      // `BootCompletenessCheck.mentionsTypeNotFound` classifies a probe
      // failure by matching this exact substring. If fabric rewords it,
      // every genuine registration gap silently reclassifies as a
      // synthesis limitation and the boot pass stops catching anything.
      val err = intercept[Throwable] {
        summon[RW[ToolInput]].write(fabric.obj("type" -> fabric.str("spec_definitely_unregistered_polytype")))
      }
      val messages = Iterator.iterate(err: Throwable)(_.getCause)
        .takeWhile(_ != null).take(10)
        .flatMap(t => Option(t.getMessage))
        .mkString(" | ")
      messages should include("Type not found")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
