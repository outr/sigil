package spec

import fabric.define.{DefType, Definition}
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.participant.DefaultAgentParticipant

/**
 * Regression guard for the `polymorphicRegistrations` ordering
 * constraint (bug #27): leaf PolyTypes (`Mode`, `WorkType`, etc.)
 * must be registered before any aggregate (`Participant`, `Tool`,
 * `Signal`) that traverses subtype `definition` eagerly via
 * `RW.poly`. If a leaf is registered too late, the aggregate's
 * `lazy val` definition snapshots an empty `Poly` and the Spice
 * Dart codegen emits an empty dispatcher.
 *
 * Concrete check: walk down `RW[DefaultAgentParticipant].definition`
 * to its `workType` field and assert the resolved `DefType.Poly`
 * has the framework's six baseline subtypes. If this fails, the
 * registration order in `Sigil.polymorphicRegistrations` has
 * regressed.
 */
class PolymorphicRegistrationOrderSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  "polymorphicRegistrations" should {
    "populate WorkType subtypes inside DefaultAgentParticipant.workType" in {
      val agentDef = summon[RW[DefaultAgentParticipant]].definition
      val workTypeField = fieldDefinition(agentDef, "workType").getOrElse {
        fail(s"Expected DefaultAgentParticipant to have a workType field; saw $agentDef")
      }
      val poly = polyOf(workTypeField).getOrElse {
        fail(s"Expected workType to resolve (through any Opt wrapper) to DefType.Poly; saw ${workTypeField.defType}")
      }
      // Six baseline framework subtypes — `ConversationWork`, `CodingWork`,
      // `AnalysisWork`, `ClassificationWork`, `CreativeWork`,
      // `SummarizationWork`. If this assertion drops to zero, the order
      // regressed and Sage's `work_type.dart` would ship an empty
      // dispatcher.
      poly.values.size should be >= 6
      poly.values.keySet should contain allOf (
        "ConversationWork", "CodingWork", "AnalysisWork",
        "ClassificationWork", "CreativeWork", "SummarizationWork"
      )
    }

    "populate Mode subtypes inside DefaultAgentParticipant chain" in {
      // ConversationMode is the only baseline; apps register more.
      val modeDef = summon[RW[sigil.provider.Mode]].definition
      val poly = polyOf(modeDef).getOrElse {
        fail(s"Expected Mode to resolve to DefType.Poly; saw ${modeDef.defType}")
      }
      poly.values.keySet should contain ("ConversationMode")
    }
  }

  /** Walk a `Definition` looking for a named field. Definitions are
    * lazy in fabric — accessing `.defType` here is the same access
    * the codegen does, so this is the test that would have caught
    * the bug. */
  private def fieldDefinition(d: Definition, name: String): Option[Definition] =
    d.defType match {
      case DefType.Obj(map) => map.get(name)
      case DefType.Opt(inner) => fieldDefinition(inner, name)
      case _ => None
    }

  /** Unwrap any `Opt(...)` layers and return the inner `Poly`, if any.
    * Fields with case-class defaults are wrapped in `Opt` even when
    * the Scala type is non-`Option`, so direct pattern matching on
    * `defType` misses real Polys. */
  private def polyOf(d: Definition): Option[DefType.Poly] =
    d.defType match {
      case p: DefType.Poly  => Some(p)
      case DefType.Opt(inner) => polyOf(inner)
      case _                => None
    }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
