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
      |Returns `{projectRoot, status, targetCount, diagnostics: [{filePath, range, severity, message, code, source}], cause?}`.
      |On ERROR, read `diagnostics` for per-file compile errors; when `diagnostics` is empty, `cause`
      |carries why (an unresolved target, a connection/build-import failure, or the build server's
      |raw error output).""".stripMargin
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
      // A request-level failure (session acquire, build import, an
      // unresolved target URI) carries its reason — a bare ERROR with
      // empty diagnostics leaves the agent blind and thrashing.
      onError = reason => BspCompileResult(input.projectRoot, "ERROR", 0, Nil, cause = Some(reason)),
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
        BspCompileTool.buildResult(input.projectRoot, status, targets.size, session.client, requestFailure = None)
      }.handleError { t =>
        // The compile REQUEST itself failed — sbt's BSP errors the
        // JSON-RPC response for some compile failures instead of
        // returning a CompileResult with statusCode ERROR. The server
        // has usually already published the per-file diagnostics on the
        // way down; collect them (plus the real target count) rather
        // than dropping to a contentless ERROR the agent can't act on.
        Task(BspCompileTool.buildResult(
          input.projectRoot,
          "ERROR",
          targets.size,
          session.client,
          requestFailure = Some(s"BSP error: ${t.getMessage}")))
      }
    }
}

object BspCompileTool {

  /**
   * Bounded tail of the rendered log text kept when it stands in for
   * missing diagnostics.
   */
  private val CauseMaxChars = 4000

  /**
   * Fallback guidance when a failed compile produced neither
   * diagnostics nor useful log output — tells the agent to stop
   * retrying blind and use the build tool directly.
   */
  private val NoDiagnosticsHint =
    "the BSP server published no diagnostics — run the build tool directly (e.g. `sbt compile`) for details"

  /**
   * Assemble the tool's result from the recording client's accumulated
   * state. Shared by the normal-return path and the failed-request
   * path so the two can't drift: `build/publishDiagnostics`
   * notifications land in the client as the server emits them, so
   * per-file diagnostics survive even when the compile request itself
   * completes exceptionally. Drains the call's log messages either way
   * (fresh slate for the next call) and surfaces them only when an
   * ERROR carries no structured diagnostics; when neither diagnostics
   * nor logs exist, the cause carries [[NoDiagnosticsHint]].
   * `requestFailure` (the failed request's own reason) is always kept
   * — alongside diagnostics when they landed, prefixing the fallback
   * text when they didn't.
   */
  def buildResult(projectRoot: String,
                  status: String,
                  targetCount: Int,
                  client: BspRecordingBuildClient,
                  requestFailure: Option[String]): BspCompileResult = {
    val typedDiags = client.diagnosticsSnapshot.toList.flatMap { case (uri, ds) =>
      val path = scala.util.Try {
        val u = new java.net.URI(uri)
        if (u.getScheme == "file") java.nio.file.Paths.get(u).toString else uri
      }.getOrElse(uri)
      ds.map(BspDiagnostic.fromBsp4j(path, _))
    }
    val logs = client.drainLogs()
    val fallback: Option[String] =
      if (status == "ERROR" && typedDiags.isEmpty)
        errorCauseFromLogs(logs).orElse(Some(NoDiagnosticsHint))
      else None
    val cause = (requestFailure, fallback) match {
      case (Some(rf), Some(fb)) => Some(s"$rf; $fb")
      case (Some(rf), None) => Some(rf)
      case (None, fb) => fb
    }
    BspCompileResult(
      projectRoot = projectRoot,
      status = status,
      targetCount = targetCount,
      diagnostics = typedDiags,
      cause = cause
    )
  }

  /**
   * Build the `cause` fallback from the build server's log messages
   * when a failed compile published no structured diagnostics.
   * Error-severity lines are preferred (the compiler's actual error
   * text); when none carry error severity the full log tail is used.
   * Bounded to the last [[CauseMaxChars]] characters. Returns `None`
   * when the server logged nothing — the status alone is all the
   * information that exists.
   */
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
