package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId}
import sigil.tool.{DiscoveryFilter, DiscoveryRequest}
import sigil.tool.fs.{BashTool, GlobTool, GrepTool, LocalFileSystemContext, ReadFileTool}

/**
 * Coverage for sigil bug #102 — filesystem tools must surface for
 * the natural-language queries agents actually write. The bug
 * cites `find_capability("read file contents source code")`
 * returning zero tool matches in production despite #96's
 * keyword broadening; this spec runs canned natural queries
 * through `DiscoveryFilter.score` and asserts the relevant
 * filesystem primitive scores high.
 *
 * Spec uses the score function directly (not a live finder)
 * because the gap is in the ranking, not the retrieval — these
 * tools' keyword sets need to cover the natural verbs/nouns.
 */
class FilesystemDiscoverySpec extends AnyWordSpec with Matchers {

  // SpaceId / ParticipantId polymorphic registry doesn't matter
  // here — we only call DiscoveryFilter.score, which doesn't
  // touch the Sigil instance.
  private val fs = LocalFileSystemContext(basePath = None)
  private val readFile = new ReadFileTool(fs)
  private val glob = new GlobTool(fs)
  private val grep = new GrepTool(fs)
  private val bash = new BashTool(fs)
  private val all = List(readFile, glob, grep, bash)

  /**
   * Score every tool against the query and rank by score
   * descending.
   */
  private def rank(query: String): List[(String, Double)] =
    all.map(t => t.name.value -> DiscoveryFilter.score(t, query))
      .sortBy(-_._2)

  "Filesystem-tool ranking against natural-language queries (#102)" should {

    "rank read_file in the top results for 'read file contents source code'" in {
      val ranked = rank("read file contents source code")
      ranked.head._1 shouldBe "read_file"
      ranked.head._2 should be > 0.0
    }

    "rank read_file high for 'read source files'" in {
      val ranked = rank("read source files")
      ranked.head._1 shouldBe "read_file"
    }

    "rank read_file high for 'view file contents'" in {
      val ranked = rank("view file contents")
      ranked.head._1 shouldBe "read_file"
    }

    "rank glob high for 'list files in directory'" in {
      val ranked = rank("list files in directory")
      ranked.head._1 shouldBe "glob"
    }

    "rank glob high for 'find files by pattern'" in {
      val ranked = rank("find files by pattern")
      // Either glob or grep can reasonably win here; assert glob is
      // top-2 (find by pattern is genuinely ambiguous).
      ranked.take(2).map(_._1) should contain("glob")
    }

    "rank grep high for 'search files for text'" in {
      val ranked = rank("search files for text")
      ranked.head._1 shouldBe "grep"
    }

    "rank grep high for 'find pattern in source code'" in {
      val ranked = rank("find pattern in source code")
      // grep wins on "pattern" + "find"; read_file may compete
      // because of "source code". Assert grep is top-2.
      ranked.take(2).map(_._1) should contain("grep")
    }

    "rank bash high for 'run shell command'" in {
      val ranked = rank("run shell command")
      ranked.head._1 shouldBe "bash"
    }

    "rank bash high for 'execute terminal command'" in {
      val ranked = rank("execute terminal command")
      ranked.head._1 shouldBe "bash"
    }
  }
}
