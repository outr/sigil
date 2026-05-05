package sigil.tooling

import ch.epfl.scala.bsp4j.StatusCode
import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tooling.types.{BspCompileResult, BspDiagnostic}

case class BspCompileInput(projectRoot: String,
                           targets: List[String] = Nil) extends ToolInput derives RW

/**
 * Compile build targets via the project's BSP server (sbt or Bloop).
 * `projectRoot` selects the persisted [[BspBuildConfig]].
 * `targets` is the list of target URIs to compile; empty means
 * "every workspace target" (the default at-rest sbt + Bloop
 * shape). Returns the BSP `StatusCode` and any diagnostics the
 * server published during the compile.
 */
final class BspCompileTool(val manager: BspManager) extends TypedTool[BspCompileInput](
  name = ToolName("bsp_compile"),
  description =
    """Compile build targets via the project's BSP server (sbt or Bloop).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is a list of target URIs; empty compiles every workspace target.
      |Returns the compile status (OK / ERROR / CANCELLED) plus any diagnostics published.""".stripMargin,
  examples = List(
    ToolExample(
      "compile all targets in a project",
      BspCompileInput(projectRoot = "/abs/path/myproject")
    )
  )
) with BspToolSupport {
  override protected def executeTyped(input: BspCompileInput, context: TurnContext): Stream[Event] =
    withSession(input.projectRoot, context) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) {
          val empty = BspCompileResult(
            projectRoot = input.projectRoot,
            status      = "NO_TARGETS",
            targetCount = 0,
            diagnostics = Nil
          )
          Task.pure(JsonFormatter.Compact(summon[RW[BspCompileResult]].read(empty)))
        }
        else session.compile(targets).map { result =>
          val status = result.getStatusCode match {
            case StatusCode.OK        => "OK"
            case StatusCode.ERROR     => "ERROR"
            case StatusCode.CANCELLED => "CANCELLED"
          }
          val diags = session.client.diagnosticsSnapshot
          val typedDiags = diags.toList.flatMap { case (uri, ds) =>
            val path = scala.util.Try {
              val u = new java.net.URI(uri)
              if (u.getScheme == "file") java.nio.file.Paths.get(u).toString else uri
            }.getOrElse(uri)
            ds.map(BspDiagnostic.fromBsp4j(path, _))
          }
          val typed = BspCompileResult(
            projectRoot = input.projectRoot,
            status      = status,
            targetCount = targets.size,
            diagnostics = typedDiags
          )
          JsonFormatter.Compact(summon[RW[BspCompileResult]].read(typed))
        }
      }
    }
}
