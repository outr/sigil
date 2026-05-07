package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.signal.Signal

/**
 * Coverage for sigil bug #53 — Dart codegen completeness for the
 * #50 + #51 trio's wire types.
 *
 * Walks `summon[RW[Signal]].definition` after a Sigil that mixes
 * WorkflowSigil has registered everything, then checks that every
 * file Sage's GenerateDart shim should emit for the new types
 * actually appears in the generator output.
 *
 * Files expected:
 *   - approve_workflow_input.dart  (sigil bug #51)
 *   - decline_workflow_input.dart  (sigil bug #51)
 *   - cancel_workflow_input.dart   (already shipping; control case)
 *   - framework_workflow_notice.dart  (sigil bug #50)
 *   - framework_workflow_phase.dart   (sigil bug #50)
 *   - cancel_framework_workflow_input.dart (sigil bug #51 — registered
 *     via CoreTools.inputRWs even though the tool isn't auto-rostered)
 */
class CodegenCompletenessSpec extends AnyWordSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  "Dart codegen" should {
    "emit framework workflow Notice + phase types" in {
      val files = generate()
      val names = files.map(_.fileName).toSet
      withClue(s"missing framework-workflow types. Names: ${names.toList.sorted.mkString(", ")}\n") {
        names should contain("framework_workflow_notice.dart")
        names should contain("framework_workflow_phase.dart")
        names should contain("framework_workflow_phase_started.dart")
        names should contain("framework_workflow_phase_step.dart")
        names should contain("framework_workflow_phase_completed.dart")
        names should contain("framework_workflow_phase_failed.dart")
      }
    }

    "emit approve / decline / cancel-framework workflow tool input types" in {
      val files = generate()
      val names = files.map(_.fileName).toSet
      withClue(s"missing one or more workflow tool input files. Names: ${names.toList.sorted.mkString(", ")}\n") {
        names should contain("cancel_workflow_input.dart")
        names should contain("approve_workflow_input.dart")
        names should contain("decline_workflow_input.dart")
        names should contain("cancel_framework_workflow_input.dart")
      }
    }
  }

  private def generate(): List[spice.openapi.generator.SourceFile] =
    spice.openapi.generator.dart.DurableSocketDartGenerator(
      spice.openapi.generator.dart.DurableSocketDartConfig(
        serviceName = "Test",
        wireType    = "Signal" -> summon[RW[Signal]].definition
      )
    ).generate()

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.sync()
  }
}
