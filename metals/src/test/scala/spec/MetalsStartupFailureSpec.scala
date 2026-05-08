package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #68 — `MetalsManager` no longer imposes
 * a wall-clock deadline on Metals startup. Instead it monitors
 * subprocess liveness and waits for `.metals/mcp.json` to appear;
 * when the subprocess exits before publishing the rendezvous file
 * the manager surfaces a diagnostic carrying the exit code and the
 * tail of recent stdout output, instead of waiting indefinitely.
 *
 * Drives `crashing-metals.sh` (a fixture that prints a few lines
 * and exits non-zero without writing `.metals/mcp.json`).
 */
class MetalsStartupFailureSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestMetalsSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 10.seconds

  private def newWorkspace(): Path = {
    val p = Files.createTempDirectory(s"metals-startup-spec-${rapid.Unique()}-")
    p.toAbsolutePath.normalize
  }

  private def deleteRecursive(p: Path): Unit = {
    if (Files.exists(p)) {
      import scala.jdk.CollectionConverters.*
      val s = Files.walk(p)
      try s.iterator().asScala.toList.reverse.foreach(x => Files.deleteIfExists(x))
      finally s.close()
    }
  }

  "MetalsManager startup failure (bug #68)" should {

    "fail with a diagnostic when the subprocess exits before writing mcp.json" in {
      val workspace = newWorkspace()
      val crashing = java.nio.file.Path
        .of("metals/src/test/resources/crashing-metals.sh")
        .toAbsolutePath.normalize.toString
      TestMetalsSigil.setLauncher(List(crashing))

      TestMetalsSigil.metalsManager.ensureRunning(workspace)
        .map(_ => Right("unexpected success"): Either[Throwable, String])
        .handleError(t => Task.pure(Left(t)))
        .map { result =>
          try {
            result match {
              case Left(t) =>
                val msg = t.getMessage
                msg should include("exited")
                msg should include("code=7")
                msg should include("FATAL: simulated crash")
              case Right(_) => fail("expected startup-failure exception")
            }
          } finally {
            TestMetalsSigil.setLauncher(List(
              java.nio.file.Path.of("metals/src/test/resources/fake-metals.sh").toAbsolutePath.normalize.toString
            ))
            deleteRecursive(workspace)
          }
        }
    }
  }

  "tear down" should {
    "dispose TestMetalsSigil" in TestMetalsSigil.shutdown.map(_ => succeed)
  }
}
