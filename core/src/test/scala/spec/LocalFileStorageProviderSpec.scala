package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.storage.{FileVersion, LocalFileStorageProvider, WriteResult}

import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, Executors}
import java.util.concurrent.atomic.AtomicInteger

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

  "LocalFileStorageProvider safe-edit" should {
    "round-trip read returning the SHA-256 hash" in {
      val bytes = "version-1".getBytes("UTF-8")
      for {
        _       <- provider.upload("safe/round-trip", bytes, "text/plain")
        contents <- provider.read("safe/round-trip")
      } yield {
        contents shouldBe defined
        contents.get.version.hash shouldBe FileVersion.hashOf(bytes)
        new String(contents.get.bytes, "UTF-8") shouldBe "version-1"
      }
    }

    "writeIfMatch with the current hash commits successfully" in {
      val initial = "v1".getBytes("UTF-8")
      val updated = "v2".getBytes("UTF-8")
      for {
        _        <- provider.upload("safe/match-success", initial, "text/plain")
        snapshot <- provider.read("safe/match-success")
        result   <- provider.writeIfMatch("safe/match-success", updated, "text/plain", snapshot.get.version)
        out      <- provider.download("safe/match-success")
      } yield {
        result shouldBe a [WriteResult.Written]
        result.asInstanceOf[WriteResult.Written].version.hash shouldBe FileVersion.hashOf(updated)
        new String(out.get, "UTF-8") shouldBe "v2"
      }
    }

    "writeIfMatch with a stale hash returns Stale carrying current contents" in {
      val initial = "v1".getBytes("UTF-8")
      val updated = "v2".getBytes("UTF-8")
      val poison  = "v3".getBytes("UTF-8")
      val staleVersion = FileVersion(FileVersion.hashOf(initial), Timestamp())
      for {
        _      <- provider.upload("safe/stale", updated, "text/plain")  // start with v2
        result <- provider.writeIfMatch("safe/stale", poison, "text/plain", staleVersion)
        out    <- provider.download("safe/stale")
      } yield {
        result shouldBe a [WriteResult.Stale]
        val stale = result.asInstanceOf[WriteResult.Stale]
        stale.current.version.hash shouldBe FileVersion.hashOf(updated)
        new String(stale.current.bytes, "UTF-8") shouldBe "v2"
        // Poison write was NOT applied
        new String(out.get, "UTF-8") shouldBe "v2"
      }
    }

    "writeIfMatch on a missing path returns NotFound" in {
      val v = FileVersion("anything", Timestamp())
      provider.writeIfMatch("safe/missing", "x".getBytes("UTF-8"), "text/plain", v).map { result =>
        result shouldBe WriteResult.NotFound
      }
    }

    "use a separate lock per canonical path" in {
      val isolated = new LocalFileStorageProvider(tmpRoot)
      for {
        _ <- isolated.upload("safe/independence/a", "1".getBytes("UTF-8"), "text/plain")
        _ <- isolated.upload("safe/independence/b", "1".getBytes("UTF-8"), "text/plain")
        snapA <- isolated.read("safe/independence/a")
        snapB <- isolated.read("safe/independence/b")
        _ <- isolated.writeIfMatch("safe/independence/a", "2".getBytes("UTF-8"), "text/plain", snapA.get.version)
        _ <- isolated.writeIfMatch("safe/independence/b", "2".getBytes("UTF-8"), "text/plain", snapB.get.version)
      } yield {
        // Two distinct path keys → two distinct lock entries. If
        // the implementation accidentally normalized to a shared
        // key, this would be 1.
        isolated.lockCount shouldBe 2
      }
    }

    "release the lock when the locked region throws, allowing subsequent CAS to proceed" in {
      val attempts = new AtomicInteger(0)
      // Subclass that throws on the first versionOf call inside the
      // locked region — drives the try/finally release path. Any
      // subsequent CAS that picks up the same path lock would
      // deadlock if the lock weren't released.
      val flaky = new LocalFileStorageProvider(tmpRoot) {
        override protected def versionOf(target: Path, bytes: Array[Byte]): FileVersion = {
          if (attempts.incrementAndGet() == 1) throw new RuntimeException("boom — simulated mid-lock failure")
          else super.versionOf(target, bytes)
        }
      }
      val initial = "v1".getBytes("UTF-8")
      val followUp = "v2".getBytes("UTF-8")
      for {
        _      <- flaky.upload("safe/exception/path", initial, "text/plain")
        // First call: enters lock, versionOf throws, exception
        // propagates through Task. Caller sees the exception; the
        // lock must have been released on the way out.
        first  <- flaky.writeIfMatch(
                    "safe/exception/path", followUp, "text/plain",
                    FileVersion(FileVersion.hashOf(initial), Timestamp())
                  ).attempt
        // Second call: the test would hang here if the lock leaked
        // (no thread released it on the throw path). We bound it
        // by giving the test a finite scalatest timeout via the
        // suite's default.
        snap   <- flaky.read("safe/exception/path")
        result <- flaky.writeIfMatch(
                    "safe/exception/path", followUp, "text/plain", snap.get.version
                  )
      } yield {
        first.isFailure shouldBe true
        first.failed.get.getMessage should include("boom")
        result shouldBe a [WriteResult.Written]
      }
    }

    "concurrent writeIfMatch — exactly one wins, others see Stale" in {
      val initial = "v0".getBytes("UTF-8")
      val concurrency = 8

      val raceProvider = new LocalFileStorageProvider(tmpRoot)
      for {
        _ <- raceProvider.upload("safe/race", initial, "text/plain")
        snapshot <- raceProvider.read("safe/race")
        version = snapshot.get.version
        _ = {
          // Drive `concurrency` simultaneous CAS attempts using a real
          // thread pool + barrier — Task.parSequence wouldn't actually
          // overlap inside a single virtual-thread carrier.
          val pool = Executors.newFixedThreadPool(concurrency)
          val gate = new CountDownLatch(1)
          val done = new CountDownLatch(concurrency)
          val results = new java.util.concurrent.ConcurrentLinkedQueue[WriteResult]()
          (0 until concurrency).foreach { i =>
            pool.submit(new Runnable {
              override def run(): Unit = {
                gate.await()
                val attempt = s"writer-$i".getBytes("UTF-8")
                val r = raceProvider.writeIfMatch("safe/race", attempt, "text/plain", version).sync()
                results.add(r)
                done.countDown()
              }
            })
          }
          gate.countDown()
          done.await()
          pool.shutdown()
          import scala.jdk.CollectionConverters.*
          val all = results.iterator().asScala.toList
          val winners = all.collect { case w: WriteResult.Written => w }
          val stales  = all.collect { case s: WriteResult.Stale => s }
          winners.size shouldBe 1
          stales.size shouldBe (concurrency - 1)
          // Every Stale carries the SAME current snapshot — namely
          // the winner's bytes, since the winner is the only writer
          // that mutated the file.
          val winnerHash = winners.head.version.hash
          stales.foreach(_.current.version.hash shouldBe winnerHash)
        }
      } yield succeed
    }
  }
}
