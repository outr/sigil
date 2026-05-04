package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.storage.StoredFile

/**
 * Integration coverage for [[sigil.Sigil.storeBytes]] /
 * [[sigil.Sigil.fetchStoredFile]] / [[sigil.Sigil.deleteStoredFile]] /
 * [[sigil.Sigil.storageUrl]] — round-trip + eager-delete + authz.
 */
class StorageSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "Sigil storage hooks" should {

    "round-trip storeBytes → fetchStoredFile under the file's space" in {
      // Authorize TestUser's chain to see TestSpace so fetch succeeds.
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(TestSpace)))
      val payload = "hello-storage".getBytes("UTF-8")
      for {
        stored <- TestSigil.storeBytes(TestSpace, payload, "text/plain",
                                       metadata = Map("test" -> "round-trip"))
        fetched <- TestSigil.fetchStoredFile(stored._id, List(TestUser))
      } yield {
        stored.space shouldBe TestSpace
        stored.path shouldBe s"${TestSpace.value}/${stored._id.value}"
        stored.contentType shouldBe "text/plain"
        stored.size shouldBe payload.length.toLong
        stored.metadata shouldBe Map("test" -> "round-trip")
        fetched shouldBe defined
        new String(fetched.get._2, "UTF-8") shouldBe "hello-storage"
        fetched.get._1._id shouldBe stored._id
      }
    }

    "return None on fetch when caller's chain isn't authorized for the file's space" in {
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set.empty))  // fail-closed
      val payload = "secret".getBytes("UTF-8")
      for {
        stored <- TestSigil.storeBytes(TestSpace, payload, "text/plain")
        fetched <- TestSigil.fetchStoredFile(stored._id, List(TestUser))
      } yield {
        fetched shouldBe None
      }
    }

    "eagerly delete record + bytes when authorized" in {
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace)))
      val payload = "wipe-me".getBytes("UTF-8")
      for {
        stored        <- TestSigil.storeBytes(GlobalSpace, payload, "text/plain")
        beforeDelete  <- TestSigil.fetchStoredFile(stored._id, List(TestUser))
        _             <- TestSigil.deleteStoredFile(stored._id, List(TestUser))
        afterDelete   <- TestSigil.fetchStoredFile(stored._id, List(TestUser))
        // Direct provider check — bytes gone from the backend too.
        rawAfter      <- TestSigil.storageProvider.download(stored.path)
      } yield {
        beforeDelete shouldBe defined
        afterDelete shouldBe None
        rawAfter shouldBe None
      }
    }

    "refuse to delete when caller's chain isn't authorized" in {
      val payload = "guarded".getBytes("UTF-8")
      for {
        // Author with auth, then strip auth before delete attempt.
        _      <- Task { TestSigil.setAccessibleSpaces(_ => Task.pure(Set(TestSpace))) }
        stored <- TestSigil.storeBytes(TestSpace, payload, "text/plain")
        _      <- Task { TestSigil.setAccessibleSpaces(_ => Task.pure(Set.empty)) }
        _      <- TestSigil.deleteStoredFile(stored._id, List(TestUser))
        // Re-grant auth and verify the bytes survived the unauthorized delete.
        _      <- Task { TestSigil.setAccessibleSpaces(_ => Task.pure(Set(TestSpace))) }
        after  <- TestSigil.fetchStoredFile(stored._id, List(TestUser))
      } yield {
        after shouldBe defined
        new String(after.get._2, "UTF-8") shouldBe "guarded"
      }
    }

    "produce a sigil:// URL by default" in {
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace)))
      val payload = "url-shape".getBytes("UTF-8")
      for {
        stored <- TestSigil.storeBytes(GlobalSpace, payload, "text/plain")
      } yield {
        val url = TestSigil.storageUrl(stored)
        url.toString should startWith ("sigil://storage/")
        url.toString should include (stored._id.value)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
