package sigil.tool.fs

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{GrepInput, GrepOutputMode}
import sigil.tool.{PlaceholderInputDetector, TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * Search files under a path for a regex pattern. Output is bounded by
 * construction (Claude Code's ripgrep shape, Sigil #346) and goes
 * straight into the agent's context — no pagination, no reference
 * handle. The caller picks an output mode and a `headLimit`; an
 * over-limit result returns the first N entries plus a note to narrow.
 * A genuinely large result is written to a file by the framework's
 * overflow path (the agent reads it with `grep` / `read_file`).
 *
 * By default the walk skips well-known noise directories (build
 * outputs, IDE state, VCS metadata, package-manager caches,
 * `.claude/` worktrees). The full segment set is
 * [[GrepTool.DefaultExcludedSegments]]; callers opt back in via
 * [[GrepInput.includeIgnored]].
 */
final class GrepTool(context: FileSystemContext) extends Tool with sigil.tool.ReadOnlyExternalTool {
  type Input = GrepInput
  type Output = TextToolOutput
  val inputRW = summon[RW[GrepInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("grep")
  val description =
    """Search files under `path` for a regex `pattern`. Output is bounded and lands directly in
      |your reply context — there is no pagination.
      |
      |`outputMode`:
      |  • `FilesWithMatches` (default) — the file paths that match. The cheap scoping pass; a broad
      |    pattern returns short paths, not a wall of lines.
      |  • `Content` — the matching lines as `file:line: text` (+ surrounding lines via `contextLines`).
      |    Use once you've narrowed the file set.
      |  • `Count` — per-file match counts.
      |
      |An optional glob pattern (e.g. `**/*.scala`) restricts the file set; `headLimit` caps returned
      |entries (default 100). Over the cap you get the first N plus a note to narrow with a more
      |specific pattern, a glob, or a subpath. To search WITHIN an earlier result, just grep the
      |file(s) it named.
      |
      |By default grep skips noise directories: .git, target, node_modules, dist, .venv, __pycache__,
      |.idea, .vscode, .metals, .bloop, .claude (Claude Code worktrees), and similar. Pass
      |`includeIgnored: true` to search inside them.""".stripMargin

  override val examples: List[ToolExample] = List(
    ToolExample("Which Scala files mention TODO", GrepInput(path = "src", pattern = "TODO", glob = Some("**/*.scala"))),
    ToolExample(
      "Show the matching lines for a definition",
      GrepInput(
        path = ".",
        pattern = "def myFunction",
        outputMode = GrepOutputMode.Content,
        contextLines = 2))
  )

  override val keywords: Set[String] = Set(
    "grep",
    "search",
    "regex",
    "find",
    "match",
    "lines",
    "lookup",
    "ripgrep",
    "rg",
    "code",
    "text",
    "files",
    "pattern",
    "scan",
    "look",
    "occurrence",
    "string"
  )

  // Bug #86 — generic primitive: ranks below domain-specific tools
  // (LSP/BSP, typed inspectors) when both match a query, but stays
  // findable when nothing more specific applies.
  override def preferIfNoBetter: Boolean = true

  override def executeResult(input: GrepInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("path" -> input.path) match {
      case Some(reason) => Task.pure(ToolResult.failure(reason))
      case None =>
        WorkspacePathResolver.resolve(ctx, input.path).flatMap { base =>
          context.searchFiles(base, input.pattern, input.glob, input.maxMatches, input.contextLines, input.includeIgnored)
            .map(matches => ToolResult.Success(TextToolOutput(render(input.outputMode, input.headLimit, matches))))
        }
    }

  /**
   * Render the matches per the chosen mode, capped at `headLimit`.
   */
  private def render(mode: GrepOutputMode, headLimit: Int, matches: List[GrepMatch]): String = mode match {
    case GrepOutputMode.FilesWithMatches =>
      capped(matches.map(_.filePath).distinct.sorted, headLimit, "files")
    case GrepOutputMode.Count =>
      val counts = matches.groupBy(_.filePath).toList.sortBy(_._1).map { case (f, ms) => s"$f: ${ms.size}" }
      capped(counts, headLimit, "files")
    case GrepOutputMode.Content =>
      val lines = matches.sortBy(m => (m.filePath, m.lineNumber)).map { m =>
        val before = m.contextBefore.map(c => s"${m.filePath}-     $c")
        val after = m.contextAfter.map(c => s"${m.filePath}-     $c")
        (before :+ s"${m.filePath}:${m.lineNumber}: ${m.content}") ++ after
      }
      capped(lines.flatten, headLimit, "matches")
  }

  private def capped(entries: List[String], headLimit: Int, unit: String): String =
    if (entries.isEmpty) "(no matches)"
    else {
      val body = entries.take(headLimit).mkString("\n")
      if (entries.size > headLimit)
        body +
          s"\n\n[showing $headLimit of ${entries.size} $unit — narrow with a more specific pattern, a glob, or a subpath to see the rest]"
      else body
    }
}

object GrepTool {

  /**
   * Path segments that mark a directory as noise — build outputs,
   * IDE state, package-manager caches, VCS metadata, and Claude
   * Code throwaway worktrees. A file is excluded when ANY segment
   * of its relative path matches one of these (parent-directory
   * check, NOT filename match).
   *
   * Conservative on purpose: every entry here is a directory that
   * almost always contains generated / duplicated / cached content
   * rather than source. Callers who specifically need to grep inside
   * one (e.g. inspecting a compiled artifact) opt in via
   * [[GrepInput.includeIgnored]].
   */
  val DefaultExcludedSegments: Set[String] = Set(
    // VCS metadata
    ".git",
    ".svn",
    ".hg",
    // IDE / editor state
    ".idea",
    ".vscode",
    ".metals",
    ".bloop",
    ".bsp",
    // Build outputs (JVM / Scala / Kotlin)
    "target",
    "build",
    "out",
    ".gradle",
    ".sbt",
    ".mill",
    ".mvn",
    // Node / JS / TS
    "node_modules",
    "dist",
    ".next",
    ".nuxt",
    ".turbo",
    ".parcel-cache",
    // Python
    ".venv",
    "venv",
    "__pycache__",
    // Other common throwaways
    ".cache",
    ".tox",
    ".pytest_cache",
    // Claude Code worktrees
    ".claude"
  )

  /**
   * True when `relPath` has any path segment matching
   * [[DefaultExcludedSegments]] or ending in `.egg-info` (Python
   * package install metadata). The check is per-segment — a file
   * literally named `target.txt` whose parent isn't `target/` is
   * NOT excluded.
   */
  def isExcluded(relPath: Path): Boolean = {
    val it = relPath.iterator().asScala
    it.exists { seg =>
      val s = seg.toString
      DefaultExcludedSegments.contains(s) || s.endsWith(".egg-info")
    }
  }
}
