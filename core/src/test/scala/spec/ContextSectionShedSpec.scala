package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, TurnInput}
import sigil.diagnostics.ProfileSection
import sigil.provider.{ContextSection, ContextSections, Placement}

/**
 * A section's shed effect is data on the section, so the curator's
 * budget cascade runs whatever the section list declares — including an
 * app's own sections — instead of matching on a fixed set of ids and
 * silently no-opping on everything else.
 */
class ContextSectionShedSpec extends AnyWordSpec with Matchers {

  private val convId = Conversation.id("section-shed-conv")

  private def turn: TurnInput = TurnInput(conversationId = convId)

  private def custom(id: ProfileSection,
                     stage: Option[Int],
                     shed: Option[TurnInput => TurnInput]): ContextSection =
    ContextSection(id, Placement.VolatileTail, stage, _ => Some("body"), shed)

  "ContextSections.shedCascade" should {

    "order the framework's sections by shed stage" in {
      val cascade = ContextSections.shedCascade(ContextSections.all)
      cascade.map(_.id) shouldBe List(
        ProfileSection.Memories, ProfileSection.Information, ProfileSection.Summaries)
      cascade.foreach(_.shed should not be empty)
      succeed
    }

    "include an app's own section at its declared stage" in {
      val mine = custom(ProfileSection.ExtraContext, Some(0),
        Some(t => t.copy(extraContext = Map.empty)))
      val cascade = ContextSections.shedCascade(ContextSections.all :+ mine)
      cascade.map(_.id).head shouldBe ProfileSection.ExtraContext
      cascade should have size 4
    }

    "apply the declared effect to the turn" in {
      val mine = custom(ProfileSection.ExtraContext, Some(0),
        Some(t => t.copy(information = Vector.empty)))
      val shed = ContextSections.shedCascade(List(mine)).head.shed.get
      shed(turn).information shouldBe empty
    }

    "reject a section declaring a shed stage with no shed effect" in {
      val broken = custom(ProfileSection.ExtraContext, Some(1), None)
      val thrown = intercept[IllegalArgumentException] {
        ContextSections.shedCascade(ContextSections.all :+ broken)
      }
      thrown.getMessage should include ("ExtraContext")
      thrown.getMessage should include ("shedStage")
    }

    "accept a section with no shed stage and no effect" in {
      val plain = custom(ProfileSection.GreetingHint, None, None)
      ContextSections.shedCascade(List(plain)) shouldBe empty
    }
  }
}
