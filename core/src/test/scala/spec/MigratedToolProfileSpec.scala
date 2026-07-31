package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.provider.ConversationMode
import sigil.tool.fs.{EditFileTool, LocalFileSystemContext}
import sigil.tool.model.{EditFileInput, GrepInput}
import sigil.tool.core.{FindCapabilityTool, RespondTool}
import sigil.tool.{
  ConsentSpec, DiscoverySpec, Effect, Freshness, MutationTarget, MutationTargeting, OutputBounds,
  TextToolOutput, ToolContext, ToolGates, ToolInput, ToolName, ToolProfile, ToolSpec
}

/**
 * Sanity over the migrated tool headers: profile-derived accessors
 * agree with the old flags' semantics, a destructive tool's wire
 * description still leads with its warning, and a consent gate still
 * derives from the profile.
 */
class MigratedToolProfileSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class GateProbeInput() extends ToolInput derives RW

  private object GatedProbeTool extends sigil.tool.Tool {
    type Input  = GateProbeInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[GateProbeInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val spec: ToolSpec = ToolSpec(
      name = ToolName("gated_probe"),
      description = "Consent-gated probe.",
      profile = ToolProfile(
        effect = Effect.Mutating(MutationTargeting.none),
        gates = ToolGates(consent = Some(ConsentSpec("Run the gated probe?")))
      ),
      discovery = DiscoverySpec(keywords = Set("test", "gate"))
    )
    override def executeOutput(input: GateProbeInput, ctx: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput("ran"))
  }

  "migrated destructive tools" should {

    "lead the wire description with the DESTRUCTIVE warning" in {
      val edit = new EditFileTool(new LocalFileSystemContext())
      edit.destructive shouldBe true
      edit.wireDescription(ConversationMode, TestSigil) should startWith("**DESTRUCTIVE.** ")
    }

    "extract the mutation target from their own input and refuse a foreign one" in {
      val edit = new EditFileTool(new LocalFileSystemContext())
      val targeting = edit.spec.profile.effect.targeting.get
      val own: ToolInput = EditFileInput(path = "src/A.scala", oldString = "a", newString = "b")
      targeting.targetOf(own) shouldBe Some(MutationTarget("src/A.scala"))
      val foreign: ToolInput = GrepInput(path = ".", pattern = "x")
      targeting.targetOf(foreign) shouldBe None
    }

    "render respond's conditional terminality headline from its consequence" in {
      RespondTool.wireDescription(ConversationMode, TestSigil) should startWith(
        "**ENDS YOUR TURN only when `endsTurn` = true"
      )
    }
  }

  "profile-derived accessors" should {

    "derive consent gating from the profile" in {
      GatedProbeTool.requiresUserConsent shouldBe true
      GatedProbeTool.consentPrompt shouldBe Some("Run the gated probe?")
    }

    "derive freshness and self-bounding for find_capability" in {
      FindCapabilityTool.freshness shouldBe Some(Freshness.Volatile)
      FindCapabilityTool.boundsOutputItself shouldBe true
      FindCapabilityTool.spec.profile.output shouldBe OutputBounds.SelfBounded
      FindCapabilityTool.readOnly shouldBe true
      FindCapabilityTool.destructive shouldBe false
    }
  }
}
