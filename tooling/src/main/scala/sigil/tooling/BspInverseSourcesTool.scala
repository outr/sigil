package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.BspInverseSourcesResult

import java.io.File

case class BspInverseSourcesInput(projectRoot: String, filePath: String) extends ToolInput derives RW

/**
 * Given a source file path, return the build targets that own it.
 * Inverse of `bsp_sources`. Useful when the agent has a file in
 * hand and wants to know "which target should I compile/test to
 * pick up this change?"
 */
final class BspInverseSourcesTool(val manager: BspManager) extends Tool with BspToolSupport {
  type Input = BspInverseSourcesInput
  type Output = BspInverseSourcesResult
  val io: ToolIO[BspInverseSourcesInput, BspInverseSourcesResult] = ToolIO.derived[BspInverseSourcesInput, BspInverseSourcesResult]

  override val name = ToolName("bsp_inverse_sources")
  override val description =
    """For a source file, return the build targets that own it.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`filePath` is the absolute source path.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("bsp", "inverse sources", "target for file", "which target", "owning target"),
      toolchain = Some("bsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: BspInverseSourcesInput, context: ToolContext): Task[BspInverseSourcesResult] =
    withSessionTyped[BspInverseSourcesResult](
      input.projectRoot,
      context,
      onError = msg => BspInverseSourcesResult(input.projectRoot, input.filePath, Nil, error = Some(msg))
    ) { session =>
      val uri = new File(input.filePath).toURI.toString
      session.inverseSources(uri).map { targets =>
        BspInverseSourcesResult(
          projectRoot = input.projectRoot,
          filePath = input.filePath,
          targets = targets.map(_.getUri)
        )
      }
    }
}
