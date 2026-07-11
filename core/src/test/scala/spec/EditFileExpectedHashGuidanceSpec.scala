package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.fs.{EditFileTool, LocalFileSystemContext}
import sigil.tool.model.EditFileInput

/**
 * Sigil #401 (sibling of #400) — `edit_file`'s optional `expectedHash` is a
 * single point-in-time snapshot that goes stale after ANY edit to the file,
 * including the agent's own previous edit. Sold as a feature ("safe-edit") with
 * no caveat, it turned a single-agent sweep into a `Stale`-retry spin: the agent
 * threaded the hash, every edit after the first failed. The behaviour is correct
 * (a real concurrency guard); the self-description must steer agents to OMIT it
 * for multi-edit sweeps and reserve it for genuine multi-writer races.
 */
class EditFileExpectedHashGuidanceSpec extends AnyWordSpec with Matchers {

  private val editFile = new EditFileTool(new LocalFileSystemContext(None))
  private val desc = editFile.description.toLowerCase

  "edit_file's expectedHash guidance (#401)" should {

    "warn that expectedHash goes stale after the agent's own edits" in {
      desc should include("stale")
      desc should (include("your own") or include("previous edit"))
    }

    "tell the agent to omit expectedHash for a multi-edit sweep" in {
      desc should include("omit")
      desc should (include("multiple edits") or include("sweep"))
    }

    "frame expectedHash as a concurrent-writer guard, not the default safe path" in {
      desc should (include("concurrent") or include("external writer"))
    }

    "carry a sweep-style example that does NOT pass expectedHash" in {
      val sweepExamples = editFile.examples.filter { ex =>
        val label = ex.description.toLowerCase
        (label.contains("sweep") || label.contains("multiple")) &&
        ex.input.asInstanceOf[EditFileInput].expectedHash.isEmpty
      }
      sweepExamples should not be empty
    }

    "label the expectedHash example as the concurrent-writer case, not generic 'safe'" in {
      val hashExamples = editFile.examples.filter(_.input.asInstanceOf[EditFileInput].expectedHash.isDefined)
      hashExamples should not be empty
      hashExamples.map(_.description.toLowerCase).foreach { label =>
        label should (include("concurrent") or include("external"))
      }
    }
  }
}
