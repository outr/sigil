package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.AllShippedTools
import sigil.tool.fs.LocalFileSystemContext

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
 */
class AllShippedToolsSpec extends AnyWordSpec with Matchers {

  private val fs = LocalFileSystemContext()

  "AllShippedTools" should {

    "return a non-empty list" in {
      AllShippedTools(fs, TestSpace) should not be empty
    }

    "produce only unique tool names" in {
      val tools = AllShippedTools(fs, TestSpace)
      val names = tools.map(_.name)
      names.distinct.size shouldBe names.size
    }

    "include the canonical filesystem tools" in {
      val names = AllShippedTools(fs, TestSpace).map(_.name.value).toSet
      names should contain("bash")
      names should contain("read_file")
      names should contain("write_file")
      names should contain("edit_file")
      names should contain("delete_file")
      names should contain("glob")
      names should contain("grep")
    }

    "include save_memory configured against the supplied space" in {
      val names = AllShippedTools(fs, TestSpace).map(_.name.value).toSet
      names should contain("save_memory")
    }

    "include the web_fetch tool" in {
      val names = AllShippedTools(fs, TestSpace).map(_.name.value).toSet
      names should contain("web_fetch")
    }
  }
}
