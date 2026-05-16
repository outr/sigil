package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
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
final class BspInverseSourcesTool(val manager: BspManager) extends TypedOutputTool[BspInverseSourcesInput, BspInverseSourcesResult](
  name = ToolName("bsp_inverse_sources"),
  description =
    """For a source file, return the build targets that own it.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`filePath` is the absolute source path.""".stripMargin,
  keywords = Set("bsp", "inverse sources", "target for file", "which target", "owning target"),
  examples = List(
    ToolExample(
      "find which target owns a file",
      BspInverseSourcesInput(
        projectRoot = "/abs/path/myproject",
        filePath = "/abs/path/myproject/core/src/main/scala/Foo.scala"
      )
    )
  )
) with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: BspInverseSourcesInput,
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
