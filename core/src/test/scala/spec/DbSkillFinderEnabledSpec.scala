package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.GlobalSpace
import sigil.provider.ConversationMode
import sigil.skill.{DbSkillFinder, Skill}
import sigil.tool.DiscoveryRequest

/**
 * Sigil #395 — `Skill.enabled = false` disables a skill WITHOUT deleting it:
 * discovery (`DbSkillFinder` / `find_capability`) filters disabled skills out
 * the same way `modes` / `space` gate visibility, and a re-enabled skill
 * surfaces again with its content intact.
 */
class DbSkillFinderEnabledSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class TestSkill(name: String,
                       description: String,
                       content: String,
                       override val keywords: Set[String] = Set.empty,
                       override val enabled: Boolean = true) extends Skill derives RW
  Skill.register(summon[RW[TestSkill]])

  private val finder = DbSkillFinder(TestSigil)
  private def request(keywords: String): DiscoveryRequest =
    DiscoveryRequest(keywords = keywords, chain = List(TestUser), mode = ConversationMode, callerSpaces = Set(GlobalSpace))

  "DbSkillFinder enabled filter (#395)" should {

    "surface an enabled skill but not a disabled one with identical content" in {
      val on  = TestSkill("widget-on",  "widget helper", "do widget things", Set("widgetkw"), enabled = true)
      val off = TestSkill("widget-off", "widget helper", "do widget things", Set("widgetkw"), enabled = false)
      for {
        _     <- TestSigil.withDB(_.skills.transaction(t => t.upsert(on).flatMap(_ => t.upsert(off))))
        found <- finder.apply(request("widgetkw"))
      } yield {
        val names = found.map(_.name).toSet
        names should contain ("widget-on")
        names should not contain "widget-off"
      }
    }

    "stop surfacing a skill when disabled, and surface it again once re-enabled (content preserved)" in {
      val skill = TestSkill("toggle-skill", "toggle helper", "TOGGLE-CONTENT", Set("togglekw"), enabled = true)
      for {
        _       <- TestSigil.withDB(_.skills.transaction(_.upsert(skill)))
        enabled <- finder.apply(request("togglekw"))
        _       <- TestSigil.withDB(_.skills.transaction(_.upsert(skill.copy(enabled = false))))
        disabled <- finder.apply(request("togglekw"))
        _       <- TestSigil.withDB(_.skills.transaction(_.upsert(skill.copy(enabled = true))))
        reEnabled <- finder.apply(request("togglekw"))
      } yield {
        enabled.map(_.name) should contain ("toggle-skill")
        disabled.map(_.name) should not contain "toggle-skill"
        reEnabled.collectFirst { case s if s.name == "toggle-skill" => s.content } shouldBe Some("TOGGLE-CONTENT")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
