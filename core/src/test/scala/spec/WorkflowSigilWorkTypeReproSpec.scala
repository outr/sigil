package spec

import fabric.define.DefType
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.WorkType
import sigil.signal.Signal

/**
 * Reproducer for sigil bug #18 — Sage (WorkflowSigil mixin) reports an
 * empty WorkType Dart class. Any type carrying a `Role` (which has a
 * `workType: WorkType` field) caches an empty WorkType polytype state in
 * its lazy-val Definition if its RW.def is forced before
 * `polymorphicRegistrations.sync()`. `DefaultAgentParticipant.roles` is
 * the canonical Role-carrying type; after registration its WorkType
 * subtypes must be populated. (Post-#346 `DelegateTaskInput.role` is a
 * flat String and no longer carries a Role.)
 */
class WorkflowSigilWorkTypeReproSpec extends AnyWordSpec with Matchers {

  "WorkflowSigil-mixed Sigil after polymorphicRegistrations" should {
    "see populated WorkType subtypes through Role's RW (sigil bug #18)" in {
      // The bug: forcing a Role-carrying type's RW.def before
      // WorkType.register has run caches an empty WorkType polytype
      // state in Role's lazy-val Definition; codegen walks through
      // Role then see empty subtypes. Booting TestWorkflowSigil via
      // polymorphicRegistrations.sync() must leave Role's WorkType
      // field populated.
      TestWorkflowSigil.polymorphicRegistrations.sync()

      // Check DefaultAgentParticipant's workType field (computed
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
