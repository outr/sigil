package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.Diagnostic
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{LspDiagnostic, LspDiagnosticsResult}

case class LspDiagnosticsInput(languageId: String,
                               filePath: String,
                               waitMs: Long = 1500L)
  extends ToolInput derives RW

/**
 *     15|  * Returns the language server's current diagnostics for a file —
 *     16|  * type errors, lint warnings, unused imports, etc. The agent's
 *     17|  * primary "did my edit compile" feedback loop. Opens the file with
 *     18|  * the server (idempotent), waits a short window for the server to
 *     19|  * publish diagnostics, then snapshots them.
 *     20|  *
 *     21|  * `waitMs` is caller-controlled because different servers settle at
 *     22|  * different speeds — Metals on cold cache can take 1–2s; rust-analyzer
 *     23|  * is sub-second after warm-up. Tools that already opened the file
 *     24|  * earlier in the turn pass `0` to read the latest snapshot directly.
 *     25|
 */
final class LspDiagnosticsTool(val manager: LspManager) extends Tool with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input = LspDiagnosticsInput
  type Output = LspDiagnosticsResult
  val inputRW = summon[RW[LspDiagnosticsInput]]
  val outputRW = summon[RW[LspDiagnosticsResult]]
  val name = ToolName("lsp_diagnostics")
  override def verification: Boolean = true
  val description =
    """Fetch the language server's diagnostics for a file (errors, warnings, hints).
      |
      |`languageId` selects the persisted LspServerConfig (e.g. "scala", "rust", "python").
      |`filePath` is the absolute path to the file. The session's project root is resolved
      |from the config's `rootMarkers` walked up from the file's directory.
      |`waitMs` (default 1500) is how long to wait for the server to publish diagnostics
      |for the file's current text after opening it. Pass 0 to read the existing snapshot only.
      |
      |Returns a verdict line (counts, or an explicit "clean" / "freshness UNKNOWN") followed by
      |one diagnostic per line, errors first. A "freshness UNKNOWN" verdict means the server did
      |NOT answer for the current text within the wait — treat the file's diagnostic state as
      |unknown; an empty stale snapshot is NOT "no issues".""".stripMargin
  override val keywords = Set(
    "lsp",
    "language",
    "diagnostics",
    "errors",
    "warnings",
    "problems",
    "lint",
    "compile-check",
    "analyze",
    "examine",
    "inspect",
    "review",
    "evaluate",
    "what's broken",
    "issues",
    "semantic",
    "scala",
    "type",
    "fix",
    "code"
  )

  override def executeOutput(input: LspDiagnosticsInput, context: ToolContext): Task[LspDiagnosticsResult] =
    withSessionOrThrow[LspDiagnosticsResult](
      input.languageId,
      input.filePath,
      context
    ) { (session, uri, _) =>
      // Capture the publish generation BEFORE the open so the wait
      // below detects the publish for THIS text — not a stale answer
      // for a previous version, and never "no answer yet" silently
      // read as clean.
      val genBefore = session.publishGeneration(uri)
      val text = scala.util.Try(java.nio.file.Files.readString(java.nio.file.Paths.get(input.filePath))).toOption.getOrElse("")
      session.didOpen(uri, input.languageId, text).flatMap { _ =>
        val freshness: Task[Boolean] =
          if (input.waitMs > 0) session.waitForDiagnostics(uri, genBefore, input.waitMs)
          else Task.pure(false) // snapshot-only read: freshness unknown
        freshness.map { fresh =>
          LspDiagnosticsResult(
            filePath = input.filePath,
            diagnostics = session.diagnosticsFor(uri).map(LspDiagnostic.fromLsp4j(input.filePath, _)),
            fresh = fresh
          )
        }
      }
    }
}
