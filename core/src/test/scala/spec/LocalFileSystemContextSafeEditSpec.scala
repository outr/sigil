package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.storage.{FileVersion, WriteResult}
import sigil.tool.fs.LocalFileSystemContext

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors}

/**
 * Coverage for [[LocalFileSystemContext]]'s safe-edit lock — distinct
 * from [[sigil.storage.LocalFileStorageProvider]]'s lock map (the
 * tool-side and storage-side abstractions each maintain their own).
 * Asserts the same three lock contracts: per-path independence,
 * exception-release, and N-thread CAS race correctness.
 */
class LocalFileSystemContextSafeEditSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private val tmpRoot: Path = Files.createTempDirectory("sigil-fs-safe-edit-")

  "LocalFileSystemContext safe-edit" should {

    "round-trip readContents returning the SHA-256 hash of the bytes" in {
      val ctx = new LocalFileSystemContext(Some(tmpRoot))
      for {
        _    <- ctx.writeFile("rt.txt", "hello")
        snap <- ctx.readContents("rt.txt")
      } yield {
        snap shouldBe defined
        snap.get.version.hash shouldBe FileVersion.hashOf("hello".getBytes(StandardCharsets.UTF_8))
        new String(snap.get.bytes, StandardCharsets.UTF_8) shouldBe "hello"
      }
    }

    "use a separate lock per canonical path" in {
      val ctx = new LocalFileSystemContext(Some(tmpRoot))
      for {
        _    <- ctx.writeFile("indep/a.txt", "1")
        _    <- ctx.writeFile("indep/b.txt", "1")
        sa   <- ctx.readContents("indep/a.txt")
        sb   <- ctx.readContents("indep/b.txt")
        _    <- ctx.writeIfMatch("indep/a.txt", "2", sa.get.version)
        _    <- ctx.writeIfMatch("indep/b.txt", "2", sb.get.version)
      } yield {
        // Two distinct canonical paths → two distinct lock entries.
        // A shared-lock bug would collapse this to 1.
        ctx.lockCount shouldBe 2
      }
    }

    "release the lock when the locked region throws, allowing subsequent CAS to succeed" in {
      val attempts = new AtomicInteger(0)
      val flaky = new LocalFileSystemContext(Some(tmpRoot)) {
        override protected def versionOf(target: Path, bytes: Array[Byte]): FileVersion =
          if (attempts.incrementAndGet() == 1) throw new RuntimeException("boom — simulated mid-lock failure")
          else super.versionOf(target, bytes)
      }
      val initialHash = FileVersion.hashOf("v1".getBytes(StandardCharsets.UTF_8))
      val staleVersion = FileVersion(initialHash, Timestamp())
      for {
        _      <- flaky.writeFile("ex/path.txt", "v1")
        first  <- flaky.writeIfMatch("ex/path.txt", "v2", staleVersion).attempt
        snap   <- flaky.readContents("ex/path.txt")
        result <- flaky.writeIfMatch("ex/path.txt", "v2", snap.get.version)
      } yield {
        first.isFailure shouldBe true
        first.failed.get.getMessage should include("boom")
        result shouldBe a [WriteResult.Written]
      }
    }

    "concurrent writeIfMatch on the same path — exactly one winner, rest see Stale" in {
      val concurrency = 8
      val ctx = new LocalFileSystemContext(Some(tmpRoot))
      for {
        _ <- ctx.writeFile("race/concurrent.txt", "v0")
        snap <- ctx.readContents("race/concurrent.txt")
        version = snap.get.version
        _ = {
          val pool = Executors.newFixedThreadPool(concurrency)
          val gate = new CountDownLatch(1)
          val done = new CountDownLatch(concurrency)
          val results = new ConcurrentLinkedQueue[WriteResult]()
          (0 until concurrency).foreach { i =>
            pool.submit(new Runnable {
              override def run(): Unit = {
                gate.await()
                val r = ctx.writeIfMatch("race/concurrent.txt", s"writer-$i", version).sync()
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
          val stales  = all.collect { case s: WriteResult.Stale   => s }
          winners.size shouldBe 1
          stales.size shouldBe (concurrency - 1)
          val winnerHash = winners.head.version.hash
          stales.foreach(_.current.version.hash shouldBe winnerHash)
        }
      } yield succeed
    }
  }
}
