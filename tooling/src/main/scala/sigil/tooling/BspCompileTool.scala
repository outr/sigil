package sigil.tooling

import ch.epfl.scala.bsp4j.{Diagnostic, DiagnosticSeverity, StatusCode}
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

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
        if (targets.isEmpty) Task.pure("No build targets to compile.")
        else session.compile(targets).map { result =>
          val status = result.getStatusCode match {
            case StatusCode.OK        => "OK"
            case StatusCode.ERROR     => "ERROR"
            case StatusCode.CANCELLED => "CANCELLED"
          }
          val diags = session.client.diagnosticsSnapshot
          val diagSummary =
            if (diags.values.forall(_.isEmpty)) ""
            else "\n" + diags.toList.map { case (uri, ds) =>
              s"$uri: ${ds.size} diagnostic(s).\n" + ds.map(renderDiag).mkString("\n")
            }.mkString("\n")
          s"Compile $status (${targets.size} target(s) in ${input.projectRoot})$diagSummary"
        }
      }
    }

  private def renderDiag(d: Diagnostic): String = {
    val sev = d.getSeverity match {
      case null                           => "unknown"
      case DiagnosticSeverity.ERROR       => "error"
      case DiagnosticSeverity.WARNING     => "warning"
      case DiagnosticSeverity.INFORMATION => "info"
      case DiagnosticSeverity.HINT        => "hint"
    }
    val r = d.getRange
    val pos = s"${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
    s"  [$sev] $pos: ${d.getMessage}"
  }
}
