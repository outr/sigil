package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.ConversationMode
import sigil.tool.fs.{GlobTool, GrepTool, LocalFileSystemContext}
import sigil.tool.{DiscoveryRequest, InMemoryToolFinder, Tool}
import sigil.tooling.refactor.{LspRenameSymbolTool, RefactorWithInstructionTool}

/**
 * Field-repro from sigil bug #213 — an agent shaping a "find and
 * remove across files" task issued the literal "Search files"
 * discovery template (`grep search find text pattern match`) and
 * the new refactor tool scored 0, never surfacing in the top
 * results. After expanding the refactor tools' keyword sets to
 * include search-and-replace vocabulary, the same query must
 * surface `refactor_with_instruction` somewhere in the result set.
 */
class RefactorToolDiscoverabilitySpec extends AnyWordSpec with Matchers {

  /** Catalog mirroring the relevant slice of a real Sigil's tool
    * roster: the grep / glob primitives the agent reaches for first,
    * plus the two refactor tools whose discoverability we're
    * validating. We don't need every framework tool — the question
    * is whether the refactor tools surface AT ALL against a
    * grep-shape query. */
  private val catalog: List[Tool] = {
    val fs = new LocalFileSystemContext(basePath = None)
    val lspManager = null.asInstanceOf[sigil.tooling.LspManager] // never invoked; we only inspect metadata
    List(
      new GrepTool(fs),
      new GlobTool(fs),
      new RefactorWithInstructionTool(fs),
      new LspRenameSymbolTool(lspManager)
    )
  }

  private val finder = InMemoryToolFinder(catalog)

  private def discover(query: String): List[Tool] =
    finder.apply(DiscoveryRequest(
      keywords     = query,
      chain        = Nil,
      mode         = ConversationMode,
      callerSpaces = Set.empty
    )).sync()

  "RefactorWithInstructionTool" should {

    "surface in the result set for the field-repro grep-shape query (bug #213)" in {
      val results = discover("grep search find text pattern match")
      // grep IS the closest match and ranks first — the fix isn't
      // about dethroning grep, it's about making the refactor tool
      // a candidate at all.
      results.headOption.map(_.name.value) shouldBe Some("grep")
      results.map(_.name.value) should contain("refactor_with_instruction")
    }

    "surface in the result set for explicit find-and-replace queries" in {
      val results = discover("find and replace across files")
      results.map(_.name.value) should contain("refactor_with_instruction")
    }
  }

  "LspRenameSymbolTool" should {

    "surface in the result set for find-symbol-shape queries (bug #213)" in {
      val results = discover("find symbol replace name change identifier")
      results.map(_.name.value) should contain("lsp_rename_symbol")
    }
  }
}
