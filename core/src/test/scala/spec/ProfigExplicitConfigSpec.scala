package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import profig.Profig

/**
 * `Sigil.instance` loads Profig defaults (files, env, system
 * properties) without discarding configuration merged programmatically
 * beforehand. profig 3.8's `loadDefaults()` REPLACES the whole
 * configuration, so the instance path must capture and re-merge the
 * explicit values — otherwise every app- or test-supplied setting
 * (`sigil.dbPath`, `sigil.postgres.jdbcUrl`, …) silently reverts to
 * its default the moment the instance boots.
 */
class ProfigExplicitConfigSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "Sigil.instance" should {
    "preserve explicitly merged Profig configuration across the defaults load" in
      // initFor merged the per-suite path and then booted the instance;
      // a defaults load that clobbers explicit values reverts this to
      // "db/sigil".
      Profig("sigil.dbPath").as[String].should(endWith("ProfigExplicitConfigSpec"))
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
