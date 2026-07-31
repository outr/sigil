package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.compiletime.testing.typeChecks

/**
 * Pins `ConsultTool.invoke`'s path-dependent typing: the type parameter
 * must be the passed tool's own `Input`. A mismatch used to compile and
 * return `None` forever; it is now a compile error.
 */
class ConsultToolTypeSafetySpec extends AnyWordSpec with Matchers {

  "ConsultTool.invoke" should {

    "compile when the type parameter matches the tool's Input" in {
      typeChecks(
        """
        import lightdb.id.Id
        import sigil.Sigil
        import sigil.db.Model
        import sigil.participant.ParticipantId
        import sigil.tool.consult.{ConsultTool, ExtractMemoriesInput, ExtractMemoriesTool}
        def probe(host: Sigil, modelId: Id[Model], chain: List[ParticipantId]) =
          ConsultTool.invoke[ExtractMemoriesInput](host, modelId, chain, "sys", "usr", ExtractMemoriesTool)
        """
      ) shouldBe true
    }

    "not compile when the type parameter mismatches the tool's Input" in {
      typeChecks(
        """
        import lightdb.id.Id
        import sigil.Sigil
        import sigil.db.Model
        import sigil.participant.ParticipantId
        import sigil.tool.consult.{ConsultTool, ExtractMemoriesTool}
        import sigil.tool.model.RespondInput
        def probe(host: Sigil, modelId: Id[Model], chain: List[ParticipantId]) =
          ConsultTool.invoke[RespondInput](host, modelId, chain, "sys", "usr", ExtractMemoriesTool)
        """
      ) shouldBe false
    }
  }
}
