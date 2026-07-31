package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{LspLocation, LspLocationsResult}

case class LspImplementationInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int) extends ToolInput derives RW

/**
 * For a trait / interface / abstract method position, list every
 * concrete implementation across the workspace. Inverse of
 * goto-definition for inheritance hierarchies — `goto_definition`
 * lands on the abstract method, `implementation` lands on each
 * subclass's override.
 *
 * Emits `LspLocationsResult`; empty when no implementations.
 */
final class LspImplementationTool(val manager: LspManager) extends Tool
  with LspToolSupport {
  type Input  = LspImplementationInput
  type Output = LspLocationsResult
  val inputRW  = summon[RW[LspImplementationInput]]
  val outputRW = summon[RW[LspLocationsResult]]

  override val name = ToolName("lsp_implementation")
  override val description =
    """List concrete implementations of an abstract symbol (trait method, interface method, abstract def).
      |
      |`languageId` + `filePath` identify the source document.
      |`line` + `character` (0-based) point at the abstract symbol.
      |Returns `[{uri, filePath, range}]`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set(
        "lsp", "implementation", "implementations", "who implements", "implementors",
        "concrete", "subclasses", "traits", "interface", "examine", "inspect"
      ),
      toolchain = Some("lsp")
    )
  )

  override def executeOutput(input: LspImplementationInput, context: ToolContext): Task[LspLocationsResult] =
    withOpenDocumentOrThrow[LspLocationsResult](
      input.languageId, input.filePath, context
    ) { (session, uri) =>
      session.implementation(uri, input.line, input.character)
        .map(locs => LspLocationsResult(locs.map(LspLocation.fromLsp4j)))
    }
}
