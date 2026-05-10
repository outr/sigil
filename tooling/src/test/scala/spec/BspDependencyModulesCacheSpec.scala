package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.tooling.BspDependencyModulesTool
import sigil.tooling.types.BspDependencyModulesResult

import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * Coverage for [[BspDependencyModulesTool]]'s in-memory cache —
 * keyed by (projectRoot, sorted targets, build.sbt mtime, build.sbt
 * SHA-256 hash). Cache hit returns the cached result without
 * touching the BSP server; cache miss recomputes. `invalidate()`
 * clears between tests so per-suite isolation holds.
 */
class BspDependencyModulesCacheSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def newProjectRoot(): Path = {
    val dir = Files.createTempDirectory(s"bsp-cache-${rapid.Unique()}-")
    Files.writeString(
      dir.resolve("build.sbt"),
      "scalaVersion := \"3.8.3\"\n",
      StandardOpenOption.CREATE
    )
    dir.toAbsolutePath
  }

  "BspDependencyModulesTool.cacheKeyFor" should {

    "return Some(key) when build.sbt exists" in {
      val root = newProjectRoot()
      BspDependencyModulesTool.cacheKeyFor(root.toString, targets = Nil).map { keyOpt =>
        try keyOpt shouldBe defined
        finally Files.deleteIfExists(root.resolve("build.sbt"))
        keyOpt.get.projectRoot shouldBe root.toString
        keyOpt.get.targets shouldBe Nil
        keyOpt.get.buildSbtHash should not be empty
      }
    }

    "return None when build.sbt is missing" in {
      val emptyDir = Files.createTempDirectory(s"bsp-no-buildsbt-${rapid.Unique()}-")
      BspDependencyModulesTool.cacheKeyFor(emptyDir.toAbsolutePath.toString, targets = Nil).map { keyOpt =>
        keyOpt shouldBe None
      }
    }

    "sort targets so the key is order-invariant" in {
      val root = newProjectRoot()
      for {
        a <- BspDependencyModulesTool.cacheKeyFor(root.toString, List("uri-b", "uri-a", "uri-c"))
        b <- BspDependencyModulesTool.cacheKeyFor(root.toString, List("uri-a", "uri-b", "uri-c"))
      } yield {
        a.get.targets shouldBe List("uri-a", "uri-b", "uri-c")
        b.get.targets shouldBe List("uri-a", "uri-b", "uri-c")
        a.get shouldBe b.get
      }
    }

    "produce a different key when build.sbt content changes" in {
      val root = newProjectRoot()
      for {
        before <- BspDependencyModulesTool.cacheKeyFor(root.toString, Nil)
        _      = Files.writeString(
                   root.resolve("build.sbt"),
                   "scalaVersion := \"3.8.3\"\nlibraryDependencies += \"org.foo\" %% \"bar\" % \"1.0\"\n",
                   StandardOpenOption.TRUNCATE_EXISTING
                 )
        after  <- BspDependencyModulesTool.cacheKeyFor(root.toString, Nil)
      } yield {
        before.get.buildSbtHash should not equal after.get.buildSbtHash
        // mtime *might* be the same (sub-millisecond), but the hash
        // is the load-bearing invalidation signal.
      }
    }
  }

  "BspDependencyModulesTool.cache" should {

    "round-trip a result through put / get on the same key" in rapid.Task {
      BspDependencyModulesTool.invalidate()
      val key = BspDependencyModulesTool.Key(
        projectRoot   = "/x/y",
        targets       = Nil,
        buildSbtMtime = 0L,
        buildSbtHash  = "deadbeef"
      )
      val result = BspDependencyModulesResult("/x/y", Nil)
      BspDependencyModulesTool.cache.put(key, result)
      Option(BspDependencyModulesTool.cache.get(key)) shouldBe Some(result)
      BspDependencyModulesTool.invalidate()
      Option(BspDependencyModulesTool.cache.get(key)) shouldBe None
    }
  }
}
