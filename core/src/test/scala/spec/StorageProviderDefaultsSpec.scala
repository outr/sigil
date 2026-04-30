package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.storage.{FileVersion, StorageProvider, WriteResult}

import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies the default `read` + `writeIfMatch` implementations on
 * the [[StorageProvider]] trait. Apps that ship custom providers
 * commonly only override the four base methods (upload / download /
 * delete / exists) and inherit the safe-edit defaults — those
 * defaults are documented as not race-safe but MUST be sequentially
 * correct (right hash on read, correct Stale / NotFound semantics
 * on writeIfMatch).
 *
 * The fixture is a small in-memory provider that overrides only the
 * four base methods, exercising the trait defaults end-to-end.
 */
class StorageProviderDefaultsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** In-memory [[StorageProvider]] with deliberately minimal
    * overrides — only the four base methods. read + writeIfMatch
    * fall through to the trait defaults. */
  private final class InMemoryProvider extends StorageProvider {
    private val store: ConcurrentHashMap[String, Array[Byte]] = new ConcurrentHashMap()
    override def upload(path: String, data: Array[Byte], contentType: String): Task[String] = Task {
      store.put(path, data); path
    }
    override def download(path: String): Task[Option[Array[Byte]]] = Task(Option(store.get(path)))
    override def delete(path: String): Task[Unit] = Task { store.remove(path); () }
    override def exists(path: String): Task[Boolean] = Task(store.containsKey(path))
  }

  "StorageProvider trait defaults" should {

    "read returns bytes plus SHA-256 hash" in {
      val p = new InMemoryProvider
      val bytes = "default-read".getBytes("UTF-8")
      for {
        _    <- p.upload("k", bytes, "text/plain")
        snap <- p.read("k")
      } yield {
        snap shouldBe defined
        snap.get.version.hash shouldBe FileVersion.hashOf(bytes)
        new String(snap.get.bytes, "UTF-8") shouldBe "default-read"
      }
    }

    "read returns None when the path is missing" in {
      val p = new InMemoryProvider
      p.read("never").map(_ shouldBe None)
    }

    "writeIfMatch with the current hash commits and returns Written" in {
      val p = new InMemoryProvider
      val initial = "v1".getBytes("UTF-8")
      val updated = "v2".getBytes("UTF-8")
      for {
        _      <- p.upload("k", initial, "text/plain")
        snap   <- p.read("k")
        result <- p.writeIfMatch("k", updated, "text/plain", snap.get.version)
        bytes  <- p.download("k")
      } yield {
        result shouldBe a [WriteResult.Written]
        result.asInstanceOf[WriteResult.Written].version.hash shouldBe FileVersion.hashOf(updated)
        new String(bytes.get, "UTF-8") shouldBe "v2"
      }
    }

    "writeIfMatch with a stale hash returns Stale carrying current contents (no write applied)" in {
      val p = new InMemoryProvider
      val initial = "v1".getBytes("UTF-8")
      val staleVersion = FileVersion(FileVersion.hashOf(initial), Timestamp())
      val current = "v2".getBytes("UTF-8")
      val poison = "v3".getBytes("UTF-8")
      for {
        _      <- p.upload("k", current, "text/plain")  // v2 already in place
        result <- p.writeIfMatch("k", poison, "text/plain", staleVersion)
        bytes  <- p.download("k")
      } yield {
        result shouldBe a [WriteResult.Stale]
        val stale = result.asInstanceOf[WriteResult.Stale]
        stale.current.version.hash shouldBe FileVersion.hashOf(current)
        new String(stale.current.bytes, "UTF-8") shouldBe "v2"
        // Poison was NOT applied
        new String(bytes.get, "UTF-8") shouldBe "v2"
      }
    }

    "writeIfMatch on a missing path returns NotFound (no write attempted)" in {
      val p = new InMemoryProvider
      val v = FileVersion("anything", Timestamp())
      for {
        result <- p.writeIfMatch("missing", "x".getBytes("UTF-8"), "text/plain", v)
        exists <- p.exists("missing")
      } yield {
        result shouldBe WriteResult.NotFound
        exists shouldBe false
      }
    }
  }
}
