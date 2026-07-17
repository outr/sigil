package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.DiscoveryFilter
import sigil.tool.fs.{EditAtRangeTool, EditFileTool, LocalFileSystemContext}

/**
 * Sigil #400 — `edit_at_range` is a point-in-time tool: its coordinates and
 * `expectedHash` reflect the file as last read, so it's wrong for sweeping
 * multiple edits across a file (the second edit lands on shifted lines / a stale
 * hash). Its over-broad keywords (`rewrite`/`patch`/`refactor`) made
 * `find_capability` float it ABOVE the text-anchored `edit_file` for exactly the
 * broad-edit intents it's wrong for. Discovery must route those to `edit_file`.
 */
class EditToolDiscoveryRankingSpec extends AnyWordSpec with Matchers {

  private val fs = new LocalFileSystemContext(None)
  private val editFile = new EditFileTool(fs)
  private val editAtRange = new EditAtRangeTool(fs)

  private def ranksEditFileFirst(intent: String): Unit = {
    val fileScore = DiscoveryFilter.score(editFile, intent)
    val rangeScore = DiscoveryFilter.score(editAtRange, intent)
    withClue(s"intent='$intent': edit_file=$fileScore edit_at_range=$rangeScore — ") {
      fileScore should be > rangeScore
    }
  }

  "find_capability ranking for broad-edit intents (#400)" should {

    "rank edit_file above edit_at_range for a refactor/patch sweep" in
      ranksEditFileFirst("refactor and patch every file in the codebase")

    "rank edit_file above edit_at_range for a rewrite sweep" in
      ranksEditFileFirst("rewrite the imports in every file")
  }

  "edit_at_range self-description (#400)" should {

    "still surface for a genuinely position-based intent (line/column/range)" in {
      // The position-specific keywords are kept, so range/line/column intents
      // still find it.
      DiscoveryFilter.score(editAtRange, "edit a range at a specific line and column") should be > 0.0
    }

    "name the text-anchored sibling so the model has somewhere to go" in {
      editAtRange.description should include("edit_file")
    }
  }
}
