package spec

import fabric.define.DefType
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.WorkType
import sigil.signal.Signal
import sigil.workflow.AgentDecisionStepInput

/**
 * Reproducer for sigil bug #18 — Sage (WorkflowSigil mixin) reports an
 * empty WorkType Dart class. WorkflowSigil's trait body eagerly registers
 * `WorkflowStepInput` subtypes — including AgentDecisionStepInput, which
 * carries a Role with a WorkType field. If that registration runs before
 * `polymorphicRegistrations.sync()`, AgentDecisionStepInput / Role lazy-val
 * Definitions cache an empty WorkType polytype state. Subsequent codegen
 * walks may inherit that empty snapshot.
 */
class WorkflowSigilWorkTypeReproSpec extends AnyWordSpec with Matchers {

  "WorkflowSigil-mixed Sigil after polymorphicRegistrations" should {
    "see populated WorkType subtypes through Role's RW (sigil bug #18)" in {
      // The bug: WorkflowSigil's trait body used to register
      // `WorkflowStepInput` at trait-init time, forcing
      // `AgentDecisionStepInput.RW.def` (which contains `Role`, which
      // has `workType: WorkType`). Without WorkType.register having
      // run yet, Role's lazy-val Definition cached an empty WorkType
      // polytype state. Codegen walks through Role saw empty subtypes.
      //
      // Fix: WorkflowSigil's registrations now run via
      // `mixinPolymorphicRegistrations`, AFTER the framework leaves.
      // TestWorkflowSigil mixes WorkflowSigil — booting it via
      // polymorphicRegistrations.sync() should leave Role's WorkType
      // field populated.
      TestWorkflowSigil.polymorphicRegistrations.sync()
      val agentStepDefn = summon[RW[AgentDecisionStepInput]].definition

      val roleField = agentStepDefn.defType match {
        case obj: DefType.Obj => obj.map.get("role").getOrElse(fail("no role field"))
        case other => fail(s"expected Obj, got $other")
      }
      val roleDefn = roleField.defType match {
        case DefType.Opt(inner) => inner
        case _ => roleField
      }
      val workTypeField = roleDefn.defType match {
        case obj: DefType.Obj => obj.map.get("workType").getOrElse(fail("Role has no workType"))
        case other => fail(s"Role expected Obj, got $other")
      }
      val workTypeInner = workTypeField.defType match {
        case DefType.Opt(inner) => inner
        case _ => workTypeField
      }
      workTypeInner.defType match {
        case p: DefType.Poly =>
          p.values.keys should contain("ConversationWork")
          p.values.keys should contain("CodingWork")
        case other => fail(s"workType expected Poly, got $other")
      }

      // Now check DefaultAgentParticipant's workType field (computed
      // AFTER polymorphicRegistrations.sync()).
      val participantDefn = summon[RW[sigil.participant.DefaultAgentParticipant]].definition
      val pWorkType = participantDefn.defType match {
        case obj: DefType.Obj => obj.map.get("workType").getOrElse(fail("no workType"))
        case other => fail(s"$other")
      }
      val pInner = pWorkType.defType match {
        case DefType.Opt(i) => i
        case _ => pWorkType
      }
      pInner.defType match {
        case p: DefType.Poly =>
          p.values.keys should contain("ConversationWork")
        case other => fail(s"$other")
      }

      // And check what DefaultAgentParticipant.roles field carries —
      // its Role's workType field should match Role's (cached empty).
      val rolesField = participantDefn.defType match {
        case obj: DefType.Obj => obj.map.get("roles").getOrElse(fail("no roles"))
        case _ => fail("not Obj")
      }
      // roles is Arr(Role)
      val roleInner = rolesField.defType match {
        case DefType.Arr(inner) => inner
        case _ => rolesField
      }
      val roleWorkTypeField = roleInner.defType match {
        case obj: DefType.Obj => obj.map.get("workType").getOrElse(fail("no role.workType"))
        case _ => fail("Role not Obj")
      }
      val roleWorkTypeInner = roleWorkTypeField.defType match {
        case DefType.Opt(i) => i
        case _ => roleWorkTypeField
      }
      roleWorkTypeInner.defType match {
        case p: DefType.Poly =>
          // The bug-#18 assertion — without the fix, this is empty.
          p.values.keys should contain("ConversationWork")
        case other => fail(s"$other")
      }

      succeed
    }
  }
}
