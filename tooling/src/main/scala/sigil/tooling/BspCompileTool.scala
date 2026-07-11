package sigil.tooling

import ch.epfl.scala.bsp4j.StatusCode
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
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
final class BspCompileTool(val manager: BspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with BspToolSupport {
  type Input  = BspCompileInput
  type Output = BspCompileResult
  val inputRW  = summon[RW[BspCompileInput]]
  val outputRW = summon[RW[BspCompileResult]]

  val name = ToolName("bsp_compile")
  val description =
    """Compile build targets via the project's BSP server (sbt or Bloop).
      |
      |`projectRoot` selects the persisted BspBuildConfig.
      |`targets` (optional) is a list of target URIs; empty compiles every workspace target.
      |Returns `{projectRoot, status, targetCount, diagnostics: [{filePath, range, severity, message, code, source}], cause?}`.
      |On ERROR, read `diagnostics` for per-file compile errors; when `diagnostics` is empty, `cause`
      |carries why (an unresolved target, a connection/build-import failure, or the build server's
      |raw error output).""".stripMargin
  override val keywords = Set(
    "bsp", "compile", "build", "type-check", "verify",
    "errors", "warnings", "compile-check", "examine", "inspect",
    "analyze", "review",
    "scala", "sbt", "project", "targets", "evaluate", "validate",
    "rebuild", "diagnostics", "fix"
  )


  override def executeOutput(input: BspCompileInput, context: ToolContext): Task[BspCompileResult] =
    withTargets[BspCompileResult](
      input.projectRoot, context, input.targets,
      // A request-level failure (session acquire, build import, an
      // unresolved target URI) carries its reason — a bare ERROR with
      // empty diagnostics leaves the agent blind and thrashing.
      onError = reason => BspCompileResult(input.projectRoot, "ERROR", 0, Nil, cause = Some(reason)),
      emptyResult = BspCompileResult(
        projectRoot = input.projectRoot,
        status      = "NO_TARGETS",
        targetCount = 0,
        diagnostics = Nil
      )
    ) { (session, targets) =>
      session.compile(targets).map { result =>
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
        // Drain the call's log messages either way (fresh slate for the
        // next call); surface them only when the compile failed WITHOUT
        // structured diagnostics — the bounded raw-output fallback that
        // keeps an ERROR actionable.
        val logs = session.client.drainLogs()
        val cause =
          if (status == "ERROR" && typedDiags.isEmpty) BspCompileTool.errorCauseFromLogs(logs)
          else None
        BspCompileResult(
          projectRoot = input.projectRoot,
          status      = status,
          targetCount = targets.size,
          diagnostics = typedDiags,
          cause       = cause
        )
      }
    }
}

object BspCompileTool {

  /** Bounded tail of the rendered log text kept when it stands in for
    * missing diagnostics. */
  private val CauseMaxChars = 4000

  /** Build the `cause` fallback from the build server's log messages
    * when a failed compile published no structured diagnostics.
    * Error-severity lines are preferred (the compiler's actual error
    * text); when none carry error severity the full log tail is used.
    * Bounded to the last [[CauseMaxChars]] characters. Returns `None`
    * when the server logged nothing — the status alone is all the
    * information that exists. */
  def errorCauseFromLogs(logs: List[ch.epfl.scala.bsp4j.LogMessageParams]): Option[String] = {
    val messages = logs.flatMap(l => Option(l.getMessage)).filter(_.trim.nonEmpty)
    if (messages.isEmpty) None
    else {
      val errors = logs.filter(_.getType == ch.epfl.scala.bsp4j.MessageType.ERROR)
        .flatMap(l => Option(l.getMessage)).filter(_.trim.nonEmpty)
      val selected = if (errors.nonEmpty) errors else messages
      val joined = selected.mkString("\n")
      val bounded = if (joined.length <= CauseMaxChars) joined else "…" + joined.takeRight(CauseMaxChars)
      Some(s"compile failed without structured diagnostics; build server output:\n$bounded")
    }
  }
}
