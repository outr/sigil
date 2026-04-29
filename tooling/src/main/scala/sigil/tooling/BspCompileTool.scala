package sigil.tooling

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, StatusCode}
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

import scala.jdk.CollectionConverters.*

case class BspCompileInput(projectRoot: String,
                           targets: List[String] = Nil) extends ToolInput derives RW

/**
 * Compile build targets via the project's BSP server (sbt / Bloop).
 * `projectRoot` selects the persisted [[BspBuildConfig]].
 * `targets` is the list of target URIs to compile; empty means
 * "every workspace target" (the default at-rest sbt + Bloop
 * shape). Returns the BSP `StatusCode` (`OK` / `ERROR` /
 * `CANCELLED`).
 *
 * Diagnostics arrive via `build/publishDiagnostics` notifications;
 * the proof-of-concept client drops them. Apps that want the
 * compile output rendered into the response subclass the build
 * client and capture the stream — the session boundary keeps that
 * customization out of the framework default.
 */
final class BspCompileTool(manager: BspManager) extends TypedTool[BspCompileInput](
  name = ToolName("bsp_compile"),
  description =
    """Compile build targets via the project's BSP server (sbt or Bloop).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is a list of target URIs; empty compiles every workspace target.
      |Returns the compile status (OK / ERROR / CANCELLED) and the count of compiled targets.""".stripMargin,
  examples = List(
    ToolExample(
      "compile all targets in a project",
      BspCompileInput(projectRoot = "/abs/path/myproject")
    ),
    ToolExample(
      "compile a specific target",
      BspCompileInput(
        projectRoot = "/abs/path/myproject",
        targets = List("file:///abs/path/myproject/?id=core")
      )
    )
  )
) {
  override protected def executeTyped(input: BspCompileInput, context: TurnContext): Stream[Event] = {
    val task = manager.session(input.projectRoot).flatMap { session =>
      val targetsTask: Task[List[BuildTargetIdentifier]] =
        if (input.targets.nonEmpty)
          Task.pure(input.targets.map(uri => new BuildTargetIdentifier(uri)))
        else
          session.workspaceBuildTargets.map(_.map(_.getId))

      targetsTask.flatMap { targets =>
        if (targets.isEmpty)
          Task.pure(reply(context, "No build targets to compile.", isError = false))
        else
          session.compile(targets).map { result =>
            val status = result.getStatusCode match {
              case StatusCode.OK        => "OK"
              case StatusCode.ERROR     => "ERROR"
              case StatusCode.CANCELLED => "CANCELLED"
            }
            reply(context, s"Compile $status (${targets.size} target(s) in ${input.projectRoot})", isError = result.getStatusCode != StatusCode.OK)
          }
      }
    }.handleError { e =>
      Task.pure(reply(context, s"BSP error: ${e.getMessage}", isError = true))
    }
    Stream.force(task.map(Stream.emit))
  }

  private def reply(context: TurnContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )
}
