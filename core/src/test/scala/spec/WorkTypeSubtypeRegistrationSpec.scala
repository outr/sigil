package spec

import fabric.define.DefType
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.WorkType

/**
 * Coverage for sigil bug #18 — the Dart codegen renders an empty
 * `WorkType` abstract class. Confirms whether the subtype list is
 * actually populated post-`polymorphicRegistrations`, and serves as
 * a regression guard once it is.
 */
class WorkTypeSubtypeRegistrationSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "WorkType polytype" should {
    "include the framework-shipped subtypes after polymorphicRegistrations" in {
      val defn = summon[RW[WorkType]].definition
      defn.defType match {
        case p: DefType.Poly =>
          val keys = p.values.keys.toSet
          keys should contain("ConversationWork")
          keys should contain("CodingWork")
          keys should contain("AnalysisWork")
          keys should contain("ClassificationWork")
          keys should contain("CreativeWork")
          keys should contain("SummarizationWork")
        case other =>
          fail(s"expected WorkType to be a polytype, got $other")
      }
    }

    "be populated when reached through DefaultAgentParticipant.workType field" in {
      val agentDefn = summon[RW[sigil.participant.DefaultAgentParticipant]].definition
      agentDefn.defType match {
        case obj: DefType.Obj =>
          val workTypeFieldDefn = obj.map.get("workType").getOrElse(
            fail(s"DefaultAgentParticipant has no `workType` field; saw ${obj.map.keys}")
          )
          val polyDefn = workTypeFieldDefn.defType match {
            case DefType.Opt(inner) => inner
            case _ => workTypeFieldDefn
          }
          polyDefn.defType match {
            case p: DefType.Poly =>
              val keys = p.values.keys.toSet
              keys should contain("ConversationWork")
              keys should contain("CodingWork")
            case other =>
              fail(s"workType field's defType is $other, not Poly")
          }
        case other =>
          fail(s"DefaultAgentParticipant defType is $other, not Obj")
      }
    }

    "render Dart subtype singletons + fromJson dispatch when run via Signal wireType (the real Sage path)" in {
      // Sage's `DartGenerator` uses `wireType = "Signal" -> summon[RW[Signal]].definition`
      // (per sigil.codegen.DartGenerator:83). Run the spice codegen
      // against the same shape and verify WorkType picks up subtypes.
      val signalDefn = summon[RW[sigil.signal.Signal]].definition
      val files = spice.openapi.generator.dart.DurableSocketDartGenerator(
        spice.openapi.generator.dart.DurableSocketDartConfig(
          serviceName = "Test",
          wireType = "Signal" -> signalDefn
        )
      ).generate()
      val workTypeFile = files.find(_.fileName == "work_type.dart").map(_.source).getOrElse(
        fail(s"work_type.dart not generated; got ${files.map(_.fileName)}")
      )
      workTypeFile should include("abstract class WorkType")
      workTypeFile should include regex """static\s+const\s+WorkType\s+conversationWork"""
      workTypeFile should include regex """static\s+const\s+WorkType\s+codingWork"""
      workTypeFile should include("if (type == 'ConversationWork')")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.sync()
  }
}
