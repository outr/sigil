package sigil.tooling

import ch.epfl.scala.bsp4j.StatusCode
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{BspCompileResult, BspDiagnostic}

case class BspCompileInput(projectRoot: String,
                           targets: List[String] = Nil)
  extends ToolInput derives RW

/**
 * Compile build targets via the project's BSP server (sbt or Bloop).
 * `projectRoot` selects the persisted [[BspBuildConfig]].
 * `targets` is the list of target URIs to compile; empty means
 * "every workspace target" (the default at-rest sbt + Bloop
 * shape). Emits a typed [[BspCompileResult]] with `status` (OK /
 * ERROR / CANCELLED / NO_TARGETS) and any diagnostics the server
 * published.
 */
final class BspCompileTool(val manager: BspManager) extends Tool with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  type Input = BspCompileInput
  type Output = BspCompileResult
  val inputRW = summon[RW[BspCompileInput]]
  val outputRW = summon[RW[BspCompileResult]]

  val name = ToolName("bsp_compile")
  val description =
    """Compile build targets via the project's BSP server (sbt or Bloop).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is a list of target URIs; empty compiles every workspace target.
      |Returns `{projectRoot, status, targetCount, diagnostics: [{filePath, range, severity, message, code, source}]}`.""".stripMargin
  override val keywords = Set(
    "bsp",
    "compile",
    "build",
    "type-check",
    "verify",
    "errors",
    "warnings",
    "compile-check",
    "examine",
    "inspect",
    "analyze",
    "review",
    "scala",
    "sbt",
    "project",
    "targets",
    "evaluate",
    "validate",
    "rebuild",
    "diagnostics",
    "fix"
  )

  override def executeOutput(input: BspCompileInput, context: ToolContext): Task[BspCompileResult] =
    withTargets[BspCompileResult](
      input.projectRoot,
      context,
      input.targets,
      onError = _ => BspCompileResult(input.projectRoot, "ERROR", 0, Nil),
      emptyResult = BspCompileResult(
        projectRoot = input.projectRoot,
        status = "NO_TARGETS",
        targetCount = 0,
        diagnostics = Nil
      )
    ) { (session, targets) =>
      session.compile(targets).map { result =>
        val status = result.getStatusCode match {
          case StatusCode.OK => "OK"
          case StatusCode.ERROR => "ERROR"
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
          status = status,
          targetCount = targets.size,
          diagnostics = typedDiags
        )
      }
    }
}
