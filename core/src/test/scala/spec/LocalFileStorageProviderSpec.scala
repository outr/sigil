package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.storage.LocalFileStorageProvider

import java.nio.file.{Files, Path}

/**
 * Coverage for [[LocalFileStorageProvider]] — round-trip the
 * upload/download/delete/exists API against a temp directory.
 * No Sigil instance, no DB — just the filesystem behavior.
 */
class LocalFileStorageProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private val tmpRoot: Path = Files.createTempDirectory("sigil-storage-spec-")
  private val provider = new LocalFileStorageProvider(tmpRoot)

  "LocalFileStorageProvider" should {
    "upload then download the same bytes" in {
      val bytes = "hello, world".getBytes("UTF-8")
      for {
        path <- provider.upload("space-a/file-1", bytes, "text/plain")
        out  <- provider.download(path)
      } yield {
        path shouldBe "space-a/file-1"
        out shouldBe defined
        new String(out.get, "UTF-8") shouldBe "hello, world"
      }
    }

    "report exists correctly before/after upload + delete" in {
      val bytes = "x".getBytes("UTF-8")
      for {
        before <- provider.exists("space-b/exists-test")
        _      <- provider.upload("space-b/exists-test", bytes, "text/plain")
        during <- provider.exists("space-b/exists-test")
        _      <- provider.delete("space-b/exists-test")
        after  <- provider.exists("space-b/exists-test")
      } yield {
        before shouldBe false
        during shouldBe true
        after shouldBe false
      }
    }

    "return None for download of a missing path" in {
      provider.download("nope/missing").map(_ shouldBe None)
    }

    "create nested directories on upload" in {
      val bytes = "deep".getBytes("UTF-8")
      for {
        _ <- provider.upload("a/b/c/d/leaf.txt", bytes, "text/plain")
      } yield {
        Files.exists(tmpRoot.resolve("a/b/c/d/leaf.txt")) shouldBe true
      }
    }
  }
}
