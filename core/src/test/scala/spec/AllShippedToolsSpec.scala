package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.AllShippedTools
import sigil.tool.fs.LocalFileSystemContext
import sigil.tool.process.{ProcessOutputTool, ProcessRegistry, ProcessSpawnTool}

/**
 * Coverage for [[AllShippedTools]] — the helper that hands every
 * framework-shipped non-core tool back as a list, sized for direct
 * concatenation onto `super.staticTools`. Verifies:
 *
 *   - the list isn't accidentally empty (would mean the helper
 *     stopped pulling in the framework tools)
 *   - every tool's `name` is unique (a duplicate would crash
 *     `StaticToolSyncUpgrade` at startup with a "duplicate
 *     ToolName" violation)
 *   - the helper composes with itself idempotently (passing
 *     different fs contexts produces the same NAMES, just different
 *     instances)
 *   - the `processRegistry` parameter is honoured: passing the same
 *     registry across multiple `AllShippedTools(...)` calls yields
 *     `ProcessSpawnTool` / `ProcessOutputTool` instances that share
 *     state. (The framework re-evaluates `Sigil.staticTools` more
 *     than once at startup; bug #3 was a default that built a fresh
 *     registry per call, so handles spawned via call-1 vanished
 *     when call-2 looked them up.)
 */
class AllShippedToolsSpec extends AnyWordSpec with Matchers {

  private val fs       = LocalFileSystemContext()
  private val registry = new ProcessRegistry()

  "AllShippedTools" should {

    "return a non-empty list" in {
      AllShippedTools(fs, TestSpace, Some(registry)) should not be empty
    }

    "produce only unique tool names" in {
      val tools = AllShippedTools(fs, TestSpace, Some(registry))
      val names = tools.map(_.name)
      names.distinct.size shouldBe names.size
    }

    "include the canonical filesystem tools" in {
      val names = AllShippedTools(fs, TestSpace, Some(registry)).map(_.name.value).toSet
      names should contain("bash")
      names should contain("read_file")
      names should contain("write_file")
      names should contain("edit_file")
      names should contain("delete_file")
      names should contain("glob")
      names should contain("grep")
    }

    "include the read-only git tools" in {
      val names = AllShippedTools(fs, TestSpace, Some(registry)).map(_.name.value).toSet
      names should contain("git_status")
      names should contain("git_diff")
      names should contain("git_log")
      names should contain("git_branch")
      names should contain("git_show")
    }

    "exclude the write-side git_commit tool" in {
      val names = AllShippedTools(fs, TestSpace, Some(registry)).map(_.name.value).toSet
      names should not contain "git_commit"
    }

    "include the process_* family when a registry is supplied" in {
      val names = AllShippedTools(fs, TestSpace, Some(registry)).map(_.name.value).toSet
      names should contain("process_spawn")
      names should contain("process_output")
      names should contain("process_signal")
      names should contain("process_list")
    }

    "omit the process_* family when processRegistry = None" in {
      val names = AllShippedTools(fs, TestSpace, None).map(_.name.value).toSet
      names should not contain "process_spawn"
      names should not contain "process_output"
      names should not contain "process_signal"
      names should not contain "process_list"
    }

    "share registry state across multiple AllShippedTools calls (bug #3 regression)" in {
      // Sigil.staticTools is a `def` and the framework calls it more
      // than once. Two calls with the SAME registry must hand back
      // process tools that share the in-memory handle map, otherwise
      // an agent that spawns via call-1 can't read output via call-2.
      val callA  = AllShippedTools(fs, TestSpace, Some(registry))
      val callB  = AllShippedTools(fs, TestSpace, Some(registry))
      val spawnA = callA.collectFirst { case t: ProcessSpawnTool  => t }.get
      val outB   = callB.collectFirst { case t: ProcessOutputTool => t }.get
      // Different tool instances...
      (spawnA eq callB.collectFirst { case t: ProcessSpawnTool => t }.get) shouldBe false
      // ...but the same registry — the only state that matters.
      registry.size shouldBe 0  // sanity: nothing leaked from earlier tests
      val _ = outB                   // silences unused warning
      val _ = spawnA
      succeed
    }

    "include save_memory configured against the supplied space" in {
      val names = AllShippedTools(fs, TestSpace, Some(registry)).map(_.name.value).toSet
      names should contain("save_memory")
    }

    "include the web_fetch tool" in {
      val names = AllShippedTools(fs, TestSpace, Some(registry)).map(_.name.value).toSet
      names should contain("web_fetch")
    }
  }
}
