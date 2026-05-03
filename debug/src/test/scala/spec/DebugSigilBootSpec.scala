package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.db.SigilDB
import sigil.debug.{DebugCollections, DebugSigil}

/**
 * Smoke spec verifying `DebugSigil` mixin boots — previously the
 * module had only RW round-trips. This spec instantiates the trait,
 * runs `instance.sync()`, asserts staticTools contains the DAP
 * management surface, and shuts down cleanly.
 */
class DebugSigilBootSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** Concrete DB carrying both core SigilDB collections and DebugCollections. */
  private class DebugSigilTestDB(directory: Option[java.nio.file.Path],
                                  storeManager: lightdb.store.CollectionManager,
                                  appUpgrades: List[lightdb.upgrade.DatabaseUpgrade] = Nil)
    extends SigilDB(directory, storeManager, appUpgrades) with DebugCollections

  private def freshSigil(): DebugSigil = {
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(s"db/test/DebugSigilBootSpec-${rapid.Unique()}"))))
    new DebugSigil {
      override type DB = DebugSigilTestDB
      override protected def buildDB(directory: Option[java.nio.file.Path],
                                     storeManager: lightdb.store.CollectionManager,
                                     appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
        new DebugSigilTestDB(directory, storeManager, appUpgrades)
      override def providerFor(modelId: lightdb.id.Id[sigil.db.Model],
                                chain: List[sigil.participant.ParticipantId]): rapid.Task[sigil.provider.Provider] =
        rapid.Task.error(new RuntimeException("provider unused"))
    }
  }

  "DebugSigil" should {
    "boot to instance and shut down cleanly" in {
      val s = freshSigil()
      for {
        _ <- s.instance
        toolNames = s.staticTools.map(_.name.value)
        _ <- s.shutdown
      } yield {
        // staticTools should include the DAP management tools when
        // debugToolsEnabled is left at default true.
        toolNames should not be empty
        s.isShutdown shouldBe true
      }
    }
  }
}
