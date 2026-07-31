package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.CodeLens
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{LspCodeLensItem, LspCodeLensResult, LspPosition}

case class LspCodeLensInput(languageId: String,
                            filePath: String) extends ToolInput derives RW

/**
 * List code lenses in a file — the small "Run | Debug" / "N
 * references" / etc. annotations editors render above method
 * declarations. Informational only: each lens's title and position
 * are surfaced for the agent's awareness; there is no execution path
 * for the lens's underlying command.
 */
final class LspCodeLensTool(val manager: LspManager) extends Tool
  with LspToolSupport {
  type Input  = LspCodeLensInput
  type Output = LspCodeLensResult
  val inputRW  = summon[RW[LspCodeLensInput]]
  val outputRW = summon[RW[LspCodeLensResult]]
  override val name = ToolName("lsp_code_lens")
  override val description =
    """List code lenses in a file (run / debug / N-references / etc. annotations).
      |
      |`languageId` + `filePath` identify the document.
      |Informational only: returns each lens's position and optional title for awareness.
      |There is no tool to execute a lens's command.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("lsp", "code lens", "lens", "inline action", "above-line action"),
      toolchain = Some("lsp")
    )
  )

  override def executeOutput(input: LspCodeLensInput, context: ToolContext): Task[LspCodeLensResult] =
    withOpenDocumentOrThrow[LspCodeLensResult](
      input.languageId, input.filePath, context
    ) { (session, uri) =>
      session.codeLens(uri).map { lenses =>
        LspCodeLensResult(filePath = input.filePath, items = lenses.map(toItem))
      }
    }

  private def toItem(lens: CodeLens): LspCodeLensItem = {
    val cmd = Option(lens.getCommand)
    LspCodeLensItem(
      position   = LspPosition.fromLsp4j(lens.getRange.getStart),
      title      = cmd.flatMap(c => Option(c.getTitle)),
      hasCommand = cmd.isDefined
    )
  }
}
