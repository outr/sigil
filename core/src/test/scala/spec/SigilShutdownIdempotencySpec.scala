package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.Sigil
import sigil.db.SigilDB

/**
 * Coverage for [[Sigil.shutdown]] semantics that the framework's
 * scaladoc claims (idempotent, codegen-safe) but which had no
 * dedicated spec. Specifically asserts:
 *
 *   1. Calling `shutdown` twice doesn't throw — `isShutdown` flips on
 *      first call and stays true.
 *   2. Calling `shutdown` on a Sigil whose `instance` was never
 *      forced (the codegen / introspection path that runs
 *      `polymorphicRegistrations` without opening the store) does NOT
 *      force the DB open just to dispose it. The instanceStarted
 *      guard is the load-bearing assertion.
 *   3. After `shutdown`, the standard predicates report the new state
 *      consistently (`isShutdown == true`).
 *
 * Each test instantiates a one-off Sigil so failures don't bleed into
 * other specs. We use `TestSigilFactory` rather than the shared
 * `TestSigil` instance because shutdown is destructive — re-using
 * the shared instance would leak shutdown state across specs.
 */
class SigilShutdownIdempotencySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** Build a fresh Sigil whose `instance` lifecycle we control
    * independently of the shared `TestSigil`. We deliberately never
    * call `instance.sync()` in these tests — shutdown's `instanceStarted`
    * guard is what we're verifying. So `buildDB` can throw — the
    * assertion is precisely that it's never invoked. */
  private def freshSigil(name: String): Sigil = new Sigil {
    override protected def buildDB(directory: Option[java.nio.file.Path],
                                    storeManager: lightdb.store.CollectionManager,
                                    appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
      throw new AssertionError(
        s"Sigil[$name].buildDB was invoked, meaning shutdown forced the DB open. " +
          s"This violates the codegen-safe shutdown invariant — instanceStarted should " +
          s"have skipped DB disposal because instance was never called."
      )
    override def providerFor(modelId: lightdb.id.Id[sigil.db.Model],
                              chain: List[sigil.participant.ParticipantId]): rapid.Task[sigil.provider.Provider] =
      rapid.Task.error(new RuntimeException("not used"))
  }

  "Sigil.shutdown" should {

    "be safe to call when `instance` was never started — no DB force-open" in {
      val s = freshSigil("never-instanced")
      // We never call s.instance.sync(); shutdown should NOT force it.
      // The instanceStarted guard at line 3579 is what makes this safe.
      s.isShutdown shouldBe false
      s.shutdown.attempt.map { result =>
        result.isSuccess shouldBe true
        s.isShutdown shouldBe true
      }
    }

    "be idempotent — second call is a no-op (does not throw)" in {
      val s = freshSigil("idempotent-double-call")
      for {
        _ <- s.shutdown
        first = s.isShutdown
        secondAttempt <- s.shutdown.attempt
      } yield {
        first shouldBe true
        secondAttempt.isSuccess shouldBe true
        s.isShutdown shouldBe true
      }
    }

    "leave `isShutdown` consistent across reads after shutdown" in {
      val s = freshSigil("isShutdown-flag")
      for {
        _ <- s.shutdown
      } yield {
        s.isShutdown shouldBe true
        s.isShutdown shouldBe true  // still true on subsequent reads
      }
    }
  }
}
