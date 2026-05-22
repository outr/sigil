package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.BspInverseSourcesResult

import java.io.File

case class BspInverseSourcesInput(projectRoot: String,
                                  filePath: String) extends ToolInput derives RW

/**
 * Given a source file path, return the build targets that own it.
 * Inverse of `bsp_sources`. Useful when the agent has a file in
 * hand and wants to know "which target should I compile/test to
 * pick up this change?"
 */
final class BspInverseSourcesTool(val manager: BspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  type Input  = BspInverseSourcesInput
  type Output = BspInverseSourcesResult
  val inputRW  = summon[RW[BspInverseSourcesInput]]
  val outputRW = summon[RW[BspInverseSourcesResult]]

  val name = ToolName("bsp_inverse_sources")
  val description =
    """For a source file, return the build targets that own it.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`filePath` is the absolute source path.""".stripMargin
  override val keywords = Set("bsp", "inverse sources", "target for file", "which target", "owning target")


  override def executeOutput(input: BspInverseSourcesInput,
                             context: TurnContext): Task[BspInverseSourcesResult] =
    withSessionTyped[BspInverseSourcesResult](
      input.projectRoot, context,
      onError = _ => BspInverseSourcesResult(input.projectRoot, input.filePath, Nil)
    ) { session =>
      val uri = new File(input.filePath).toURI.toString
      session.inverseSources(uri).map { targets =>
        BspInverseSourcesResult(
          projectRoot = input.projectRoot,
          filePath    = input.filePath,
          targets     = targets.map(_.getUri)
        )
      }
    }
}
