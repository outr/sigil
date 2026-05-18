package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tooling.BspDiscovery

import java.nio.file.{Files, Path}

/**
 * Sigil bug #20 — BspManager.session falls back to scanning
 * .bsp JSON files under the project root when no config is
 * persisted. The set_workspace → start_metals → bsp_compile flow
 * works without an out-of-band registration step.
 */
class BspDiscoverySpec extends AnyWordSpec with Matchers {

  private def withTempProject[T](setup: Path => Unit)(test: Path => T): T = {
    val root = Files.createTempDirectory("bsp-discovery-")
    try {
      setup(root)
      test(root)
    } finally {
      def deleteRecursive(p: Path): Unit = {
        if (Files.isDirectory(p)) {
          val s = Files.list(p)
          try s.iterator().forEachRemaining(deleteRecursive(_))
          finally s.close()
        }
        Files.deleteIfExists(p)
      }
      deleteRecursive(root)
    }
  }

  "BspDiscovery.scan" should {
    "find sbt.json under .bsp and produce a BspBuildConfig" in
      withTempProject { root =>
        val bspDir = Files.createDirectories(root.resolve(".bsp"))
        Files.writeString(
          bspDir.resolve("sbt.json"),
          """{"name":"sbt","version":"1.10.7","bspVersion":"2.1.0","languages":["scala"],"argv":["sbt","-Xms100m","-Xmx100m","-classpath","sbt-launcher.jar","xsbt.boot.Boot","-bsp"]}"""
        )
      } { root =>
        val cfg = BspDiscovery.scan(root.toString)
        cfg should not be empty
        cfg.get.projectRoot shouldBe root.toString
        cfg.get.command shouldBe "sbt"
        cfg.get.args shouldBe List("-Xms100m", "-Xmx100m", "-classpath", "sbt-launcher.jar", "xsbt.boot.Boot", "-bsp")
      }

    "return None when no .bsp directory exists" in
      withTempProject(_ => ())(root => BspDiscovery.scan(root.toString) shouldBe None)

    "return None when .bsp is empty" in
      withTempProject { root =>
        Files.createDirectories(root.resolve(".bsp"))
      } { root =>
        BspDiscovery.scan(root.toString) shouldBe None
      }

    "skip malformed JSON files and try the next one" in
      withTempProject { root =>
        val bspDir = Files.createDirectories(root.resolve(".bsp"))
        Files.writeString(bspDir.resolve("aaa-broken.json"), "{ not json")
        Files.writeString(
          bspDir.resolve("bbb-good.json"),
          """{"name":"bloop","argv":["bloop","bsp"]}"""
        )
      } { root =>
        val cfg = BspDiscovery.scan(root.toString)
        cfg should not be empty
        cfg.get.command shouldBe "bloop"
        cfg.get.args shouldBe List("bsp")
      }

    "return None when argv is missing or empty" in
      withTempProject { root =>
        val bspDir = Files.createDirectories(root.resolve(".bsp"))
        Files.writeString(bspDir.resolve("noargv.json"), """{"name":"weird"}""")
        Files.writeString(bspDir.resolve("emptyargv.json"), """{"argv":[]}""")
      } { root =>
        BspDiscovery.scan(root.toString) shouldBe None
      }
  }
}
