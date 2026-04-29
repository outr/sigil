package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import java.io.File

case class BspInverseSourcesInput(projectRoot: String,
                                  filePath: String) extends ToolInput derives RW

/**
 * Given a source file path, return the build targets that own it.
 * Inverse of `bsp_sources`. Useful when the agent has a file in
 * hand and wants to know "which target should I compile/test to
 * pick up this change?"
 */
final class BspInverseSourcesTool(val manager: BspManager) extends TypedTool[BspInverseSourcesInput](
  name = ToolName("bsp_inverse_sources"),
  description =
    """For a source file, return the build targets that own it.
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`filePath` is the absolute source path.""".stripMargin,
  examples = List(
    ToolExample(
      "find which target owns a file",
      BspInverseSourcesInput(
        projectRoot = "/abs/path/myproject",
        filePath = "/abs/path/myproject/core/src/main/scala/Foo.scala"
      )
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspInverseSourcesInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      val uri = new File(input.filePath).toURI.toString
      session.inverseSources(uri).map { targets =>
        if (targets.isEmpty) s"No target owns ${input.filePath}."
        else targets.map(t => s"  ${t.getUri}").mkString("\n")
      }
    }
}
