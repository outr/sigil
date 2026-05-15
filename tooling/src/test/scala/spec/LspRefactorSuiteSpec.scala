package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.ToolName
import sigil.tooling.LspRefactorSuite

class LspRefactorSuiteSpec extends AnyWordSpec with Matchers {

  "LspRefactorSuite" should {

    "list the refactor / navigation / inspection tools an agent typically wants during coding work" in {
      // Covers the core refactor surface — apps that compose this
      // into a Mode's ToolPolicy get the suite in scope without
      // requiring discovery for each tool individually.
      LspRefactorSuite.toolNames should contain allOf (
        ToolName("lsp_rename"),
        ToolName("lsp_code_action"),
        ToolName("lsp_apply_code_action"),
        ToolName("lsp_goto_definition"),
        ToolName("lsp_find_references"),
        ToolName("lsp_implementation"),
        ToolName("lsp_hover"),
        ToolName("lsp_completion"),
        ToolName("lsp_format"),
        ToolName("lsp_diagnostics")
      )
    }

    "not duplicate names" in {
      val names = LspRefactorSuite.toolNames.map(_.value)
      names.distinct.size shouldBe names.size
    }
  }
}
