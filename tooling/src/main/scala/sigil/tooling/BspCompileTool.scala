package sigil.tooling

import ch.epfl.scala.bsp4j.StatusCode
import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{BspCompileResult, BspDiagnostic}

case class BspCompileInput(projectRoot: String,
                           targets: List[String] = Nil) extends ToolInput derives RW

/**
 * Compile build targets via the project's BSP server (sbt or Bloop).
 * `projectRoot` selects the persisted [[BspBuildConfig]].
 * `targets` is the list of target URIs to compile; empty means
 * "every workspace target" (the default at-rest sbt + Bloop
 * shape). Emits a typed [[BspCompileResult]] with `status` (OK /
 * ERROR / CANCELLED / NO_TARGETS) and any diagnostics the server
 * published.
 */
final class BspCompileTool(val manager: BspManager) extends TypedOutputTool[BspCompileInput, BspCompileResult](
  name = ToolName("bsp_compile"),
  description =
    """Compile build targets via the project's BSP server (sbt or Bloop).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is a list of target URIs; empty compiles every workspace target.
      |Returns `{projectRoot, status, targetCount, diagnostics: [{filePath, range, severity, message, code, source}]}`.""".stripMargin,
  keywords = Set(
    "bsp", "compile", "build", "type-check", "verify",
    "errors", "warnings", "compile-check", "examine", "inspect",
    "analyze", "review",
    "scala", "sbt", "project", "targets", "evaluate", "validate",
    "rebuild", "diagnostics", "fix"
  ),
  examples = List(
    ToolExample(
      "compile all targets in a project",
      BspCompileInput(projectRoot = "/abs/path/myproject")
    )
  )
) with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: BspCompileInput, context: TurnContext): Task[BspCompileResult] =
    withSessionTyped[BspCompileResult](
      input.projectRoot, context,
      onError = msg => BspCompileResult(input.projectRoot, "ERROR", 0, Nil)
    ) { session =>
      targetsFromInput(session, input.targets).flatMap { targets =>
        if (targets.isEmpty) {
          Task.pure(BspCompileResult(
            projectRoot = input.projectRoot,
            status      = "NO_TARGETS",
            targetCount = 0,
            diagnostics = Nil
          ))
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
          BspCompileResult(
            projectRoot = input.projectRoot,
            status      = status,
            targetCount = targets.size,
            diagnostics = typedDiags
          )
        }
      }
    }
}
