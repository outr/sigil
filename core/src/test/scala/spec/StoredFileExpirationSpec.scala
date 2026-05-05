package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.maintenance.StoredFileExpirationSweep
import sigil.storage.StoredFileCategory

import scala.concurrent.duration.*

/**
 * Coverage for Bug #9 phase 2 — `StoredFile.category`,
 * `StoredFile.expiresAt`, and the
 * [[sigil.maintenance.StoredFileExpirationSweep]] maintenance task.
 *
 * Asserts:
 *   - `isExpired(now)` returns true once `expiresAt <= now`.
 *   - `Sigil.listStoredFiles` filters by `category` and skips
 *     expired records by default.
 *   - The sweep's `runOnce` removes every expired record (both the
 *     row and the storage-provider bytes).
 *   - Records without `expiresAt` are persistent and never reaped.
 */
class StoredFileExpirationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setAccessibleSpaces(_ => Task.pure(Set[sigil.SpaceId](TestSpace)))

  private def now: Timestamp = Timestamp()
  private def msAgo(ms: Long): Timestamp = Timestamp(now.value - ms)
  private def msAhead(ms: Long): Timestamp = Timestamp(now.value + ms)

  "StoredFile" should {
    "report `isExpired` accurately based on the current clock" in {
      val past = sigil.storage.StoredFile(
        space = TestSpace, path = "p1", contentType = "text/plain", size = 1,
        category = StoredFileCategory.ToolOutput, expiresAt = Some(msAgo(1000))
      )
      val future = past.copy(expiresAt = Some(msAhead(60_000)))
      val never  = past.copy(expiresAt = None)
      past.isExpired(now) shouldBe true
      future.isExpired(now) shouldBe false
      never.isExpired(now) shouldBe false
      Task.pure(succeed)
    }
  }

  "Sigil.listStoredFiles" should {
    "filter by category and skip expired records by default" in {
      for {
        attachment <- TestSigil.storeBytes(TestSpace, "userdata".getBytes, "text/plain",
                                           category = StoredFileCategory.UserAttachment)
        toolFresh  <- TestSigil.storeBytes(TestSpace, "fresh-output".getBytes, "text/plain",
                                           category = StoredFileCategory.ToolOutput,
                                           expiresAt = Some(msAhead(60_000)))
        toolStale  <- TestSigil.storeBytes(TestSpace, "stale-output".getBytes, "text/plain",
                                           category = StoredFileCategory.ToolOutput,
                                           expiresAt = Some(msAgo(1000)))
        userOnly   <- TestSigil.listStoredFiles(TestUser, categories = Some(Set(StoredFileCategory.UserAttachment)))
        toolDefault <- TestSigil.listStoredFiles(TestUser, categories = Some(Set(StoredFileCategory.ToolOutput)))
        toolWithExpired <- TestSigil.listStoredFiles(TestUser,
                                                     categories = Some(Set(StoredFileCategory.ToolOutput)),
                                                     includeExpired = true)
      } yield {
        userOnly.map(_.fileId).toSet shouldBe Set(attachment._id)
        toolDefault.map(_.fileId).toSet shouldBe Set(toolFresh._id)
        toolWithExpired.map(_.fileId).toSet shouldBe Set(toolFresh._id, toolStale._id)
      }
    }
  }

  "StoredFileExpirationSweep" should {
    "delete expired records and leave fresh ones in place" in {
      val sweep = StoredFileExpirationSweep(interval = 1.hour)
      for {
        fresh    <- TestSigil.storeBytes(TestSpace, "fresh".getBytes, "text/plain",
                                         category = StoredFileCategory.ToolOutput,
                                         expiresAt = Some(msAhead(60_000)))
        stale    <- TestSigil.storeBytes(TestSpace, "stale".getBytes, "text/plain",
                                         category = StoredFileCategory.ToolOutput,
                                         expiresAt = Some(msAgo(1000)))
        persist  <- TestSigil.storeBytes(TestSpace, "persist".getBytes, "text/plain",
                                         category = StoredFileCategory.UserAttachment,
                                         expiresAt = None)
        _        <- sweep.runOnce(TestSigil)
        survivor <- TestSigil.withDB(_.storedFiles.transaction(_.list)).map(_.toList.map(_._id).toSet)
      } yield {
        survivor should contain(fresh._id)
        survivor should contain(persist._id)
        survivor should not contain stale._id
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
