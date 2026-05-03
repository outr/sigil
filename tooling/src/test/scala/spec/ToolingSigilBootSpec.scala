package spec

import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.{GlobalSpace, SpaceId}
import sigil.db.SigilDB
import sigil.tooling.{ToolingCollections, ToolingSigil}

/**
 * Smoke spec verifying `ToolingSigil` mixin boots — the previous
 * coverage stopped at RW round-trips of config records and never
 * actually instantiated the trait. This spec constructs a minimal
 * `ToolingSigil`, forces `instance.sync()`, asserts the framework
 * upgrades complete, and shuts down cleanly.
 *
 * Doesn't spawn any LSP/BSP subprocess (that needs a real Metals /
 * sbt install) — those live in app-side integration specs.
 */
class ToolingSigilBootSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** Concrete DB carrying both core SigilDB collections and ToolingCollections —
    * the shape apps use when they mix in ToolingSigil. */
  private class ToolingSigilTestDB(directory: Option[java.nio.file.Path],
                                    storeManager: lightdb.store.CollectionManager,
                                    appUpgrades: List[lightdb.upgrade.DatabaseUpgrade] = Nil)
    extends SigilDB(directory, storeManager, appUpgrades) with ToolingCollections

  private def freshSigil(): ToolingSigil = {
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(s"db/test/ToolingSigilBootSpec-${rapid.Unique()}"))))
    new ToolingSigil {
      override type DB = ToolingSigilTestDB
      override protected def buildDB(directory: Option[java.nio.file.Path],
                                     storeManager: lightdb.store.CollectionManager,
                                     appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
        new ToolingSigilTestDB(directory, storeManager, appUpgrades)
      override def providerFor(modelId: lightdb.id.Id[sigil.db.Model],
                                chain: List[sigil.participant.ParticipantId]): rapid.Task[sigil.provider.Provider] =
        rapid.Task.error(new RuntimeException("provider unused"))
    }
  }

  "ToolingSigil" should {
    "boot to instance and shut down cleanly" in {
      val s = freshSigil()
      for {
        _ <- s.instance
        // The mixin extends staticTools — verify it added something
        // (the LSP/BSP management tools).
        toolNames = s.staticTools.map(_.name.value)
        _ <- s.shutdown
      } yield {
        // staticTools should include the mixin's management tools when
        // toolingToolsEnabled is left at default true.
        toolNames should not be empty
        s.isShutdown shouldBe true
      }
    }
  }
}
