package sigil.tool.fs

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.tool.model.GrepInput
import sigil.tool.output.{Node, PaginatedTool}
import sigil.tool.{ToolExample, ToolName}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * Search files under a path for a regex pattern. Tree-shaped
 * paginated output:
 *   - top-level nodes are [[GrepNode.FileMatch]] records (one
 *     per file with at least one hit)
 *   - each file node carries `hasChildren = true`; expanding it
 *     via `next_page` returns its [[GrepNode.LineMatch]] children
 *
 * The first page of files lands inline; the rest paginate via
 * [[sigil.tool.output.NextPageTool]].
 *
 * By default the walk skips well-known noise directories (build
 * outputs, IDE state, VCS metadata, package-manager caches,
 * `.claude/` worktrees). The full segment set is
 * [[GrepTool.DefaultExcludedSegments]]; callers can opt back in
 * via [[GrepInput.includeIgnored]].
 */
final class GrepTool(context: FileSystemContext) extends PaginatedTool[GrepInput, GrepNode](
  name = ToolName("grep"),
  description0 =
    """Search files under `path` for a regex pattern. An optional file-set glob restricts the
      |search; `contextLines` adds surrounding-context lines to each match.
      |
      |Returns a paginated tree: the top-level page lists files (one node per file with
      |at least one match, with `matchCount`); children are the matching lines for that
      |file.
      |
      |NOTE: By default, grep skips well-known noise directories: .git, .svn, .hg, .idea,
      |.vscode, .metals, .bloop, target, build, out, .gradle, .sbt, node_modules, dist,
      |.next, .venv, __pycache__, .claude (Claude Code worktrees), and similar. To search
      |inside these, pass `includeIgnored: true`. The default matches what you almost
      |always want: real source files, not build outputs or throwaway worktrees.""".stripMargin,
  examples = List(
    ToolExample("Find TODOs in Scala source", GrepInput(path = "src", pattern = "TODO", glob = Some("**/*.scala"))),
    ToolExample("Find function definition with context", GrepInput(path = ".", pattern = "def myFunction", contextLines = 2))
  ),
  keywords = Set(
    "grep", "search", "regex", "find", "match", "lines",
    "lookup", "ripgrep", "rg", "code", "text", "files", "pattern",
    "scan", "look", "occurrence", "string"
  )
) with sigil.tool.ReadOnlyExternalTool {
  // Bug #86 — generic primitive: ranks below domain-specific tools
  // (LSP/BSP, typed inspectors) when both match a query, but stays
  // findable when nothing more specific applies.
  override def preferIfNoBetter: Boolean = true

  // Bug #230 — match lists are the canonical input to
  // `dispatch_workers`. Surfacing the tool after a grep nudges the
  // agent toward "per-match LLM-or-script pipeline" instead of
  // browsing pages of hits by hand.
  override def suggestedNextTools: List[ToolName] = List(ToolName("dispatch_workers"))

  override protected def executeStream(input: GrepInput, ctx: TurnContext): Stream[Node[GrepNode]] =
    Stream.force(
      WorkspacePathResolver.resolve(ctx, input.path).flatMap { base =>
        context.searchFiles(base, input.pattern, input.glob, input.maxMatches, input.contextLines, input.includeIgnored).map { matches =>
          // Group by file. Each file becomes a top-level Node with
          // its line-matches as child Nodes (lazy children stream
          // built from the grouped list).
          val byFile = matches.groupBy(_.filePath).toList.sortBy(_._1)
          Stream.fromIterator(Task.pure(byFile.iterator.map { case (filePath, fileMatches) =>
            val children = Stream.fromIterator(Task.pure(
              fileMatches.iterator.map { m =>
                Node.leaf[GrepNode](GrepNode.LineMatch(
                  lineNumber    = m.lineNumber,
                  content       = m.content,
                  contextBefore = m.contextBefore,
                  contextAfter  = m.contextAfter
                ))
              }
            ))
            Node.parent[GrepNode](
              payload  = GrepNode.FileMatch(filePath = filePath, matchCount = fileMatches.size),
              children = children
            )
          }))
        }
      }
    )
}

object GrepTool {

  /** Path segments that mark a directory as noise — build outputs,
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
    ".git", ".svn", ".hg",
    // IDE / editor state
    ".idea", ".vscode", ".metals", ".bloop", ".bsp",
    // Build outputs (JVM / Scala / Kotlin)
    "target", "build", "out", ".gradle", ".sbt", ".mill", ".mvn",
    // Node / JS / TS
    "node_modules", "dist", ".next", ".nuxt", ".turbo", ".parcel-cache",
    // Python
    ".venv", "venv", "__pycache__",
    // Other common throwaways
    ".cache", ".tox", ".pytest_cache",
    // Claude Code worktrees
    ".claude"
  )

  /** True when `relPath` has any path segment matching
    * [[DefaultExcludedSegments]] or ending in `.egg-info` (Python
    * package install metadata). The check is per-segment — a file
    * literally named `target.txt` whose parent isn't `target/` is
    * NOT excluded. */
  def isExcluded(relPath: Path): Boolean = {
    val it = relPath.iterator().asScala
    it.exists { seg =>
      val s = seg.toString
      DefaultExcludedSegments.contains(s) || s.endsWith(".egg-info")
    }
  }
}
