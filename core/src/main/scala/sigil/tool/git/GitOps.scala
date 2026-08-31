package sigil.tool.git

import sigil.tool.model.{GitBranchEntry, GitCommitEntry, GitDiffHunk, GitDiffLine, GitDiffLineKind, GitFileState, GitStatusEntry}

import scala.collection.mutable

/**
 * Parsing helpers for the `sigil.tool.git` family. Each function
 * takes raw `git` CLI output and returns a typed model shape. The
 * tools themselves shell out via `FileSystemContext.executeCommand`
 * so the parsers see well-defined porcelain / pretty formats.
 */
object GitOps {

  /**
   * Single-quote a string for safe inclusion in a shell command
   * line. Wraps in single quotes and escapes any embedded single
   * quote with the `'\''` idiom, so arbitrary user-supplied paths /
   * messages / revision specs can't break out of the argument.
   */
  def shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /**
   * Branch header parse of a `git status --branch` first line:
   * `(branch, ahead, behind)`.
   */
  final case class StatusHeader(branch: String, ahead: Int, behind: Int)

  /**
   * Parse the output of `git status --porcelain=v1 --branch` into a
   * `(StatusHeader, entries)` pair.
   *
   * The branch header is the first line ("`## main...origin/main`")
   * and may include `[ahead 2]` / `[behind 1]` / `[ahead 2, behind 1]`
   * markers; subsequent lines each describe a tracked or untracked
   * entry (`XY <path>` or `R  old -> new` for renames).
   */
  def parseStatus(stdout: String): (StatusHeader, List[GitStatusEntry]) = {
    val rawLines = stdout.split('\n').toList.filter(_.nonEmpty)
    val (header, entryLines) = rawLines match {
      case h :: rest if h.startsWith("## ") => (Some(h), rest)
      case all => (None, all)
    }

    val statusHeader = header match {
      case Some(h) =>
        val body = h.stripPrefix("## ")
        // `branch...remote [ahead N, behind M]` or `branch...remote` or `HEAD (no branch)`
        val (nameRemote, marker) = body.indexOf(" [") match {
          case -1 => (body, "")
          case i => (body.substring(0, i), body.substring(i + 1))
        }
        val branchName = nameRemote.split("\\.\\.\\.", 2).headOption.getOrElse(nameRemote)
        val a = "ahead (\\d+)".r.findFirstMatchIn(marker).map(_.group(1).toInt).getOrElse(0)
        val b = "behind (\\d+)".r.findFirstMatchIn(marker).map(_.group(1).toInt).getOrElse(0)
        StatusHeader(branchName, a, b)
      case None => StatusHeader("", 0, 0)
    }

    val entries = entryLines.flatMap { line =>
      if (line.length < 3) None
      else {
        val xy = line.substring(0, 2)
        val rest = line.substring(3)
        val (path, renamedFrom) = if (xy.startsWith("R") || xy.startsWith("C")) {
          rest.split(" -> ", 2) match {
            case Array(from, to) => (to, Some(from))
            case _ => (rest, None)
          }
        } else (rest, None)

        Some(GitStatusEntry(
          path = path,
          indexState = GitFileState.fromChar(xy.substring(0, 1)),
          workingState = GitFileState.fromChar(xy.substring(1, 2)),
          renamedFrom = renamedFrom
        ))
      }
    }

    (statusHeader, entries)
  }

  /**
   * Parse the output of `git log --pretty=format:<sha>%x00<author>%x00<date>%x00<subject>%x00<body>%x1e`.
   * Records are ``-separated (record-separator); fields within
   * a record are ` `-separated (null) so subjects and bodies
   * containing newlines / pipes / commas don't fragment the record.
   */
  def parseLog(stdout: String, includeBody: Boolean): List[GitCommitEntry] = {
    val records = stdout.split('\u001e').toList.map(_.trim).filter(_.nonEmpty)
    records.map { record =>
      val parts = record.split('\u0000').padTo(5, "")
      val body = parts(4)
      GitCommitEntry(
        sha = parts(0),
        author = parts(1),
        date = parts(2),
        subject = parts(3),
        body = if (includeBody && body.nonEmpty) Some(body) else None
      )
    }
  }

  /**
   * Parse the output of `git branch -vv` (and `-a` when remotes are
   * included). Each line is `* name      sha [tracking] subject` for
   * the current branch and `  name      sha ...` for the rest.
   */
  def parseBranches(branchOutput: String, includeRemotes: Boolean): List[GitBranchEntry] = {
    val lines = branchOutput.split('\n').toList.filter(_.nonEmpty)
    lines.flatMap { line =>
      val isCurrent = line.startsWith("*")
      val body = line.drop(2).trim
      // Remote-tracking entries come back as `remotes/origin/foo abc123 ...`
      val isRemote = body.startsWith("remotes/")
      if (isRemote && !includeRemotes) None
      else if (body.isEmpty) None
      else {
        val tokens = body.split("\\s+", 3)
        val name = tokens.headOption.getOrElse("").stripPrefix("remotes/")
        val sha = if (tokens.length >= 2) tokens(1) else ""
        val rest = if (tokens.length >= 3) tokens(2) else ""
        val tracking = "\\[([^\\]]+)\\]".r.findFirstMatchIn(rest).map(_.group(1))
        Some(GitBranchEntry(
          name = name,
          sha = sha,
          isCurrent = isCurrent,
          isRemote = isRemote,
          tracking = tracking
        ))
      }
    }
  }

  /**
   * Parse a unified diff into per-file hunks. `parseDiff` is used by
   * [[GitDiffTool]] when the agent passes `format = "hunks"` and by
   * [[GitShowTool]] for the commit's diff body.
   */
  def parseDiff(diffText: String): List[GitDiffHunk] = {
    val hunks = mutable.ListBuffer.empty[GitDiffHunk]
    var currentFile = ""
    var currentOld = 0
    var currentNew = 0
    val currentLines = mutable.ListBuffer.empty[GitDiffLine]
    var hunkOpen = false
    val HunkHeader = "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*".r

    def closeHunk(): Unit = if (hunkOpen) {
      hunks += GitDiffHunk(
        file = currentFile,
        oldStart = currentOld,
        newStart = currentNew,
        lines = currentLines.toList
      )
      currentLines.clear()
      hunkOpen = false
    }

    diffText.split('\n').foreach {
      case line if line.startsWith("diff --git ") =>
        closeHunk()
        // `diff --git a/foo b/bar` — prefer the b/-side as the file
        val parts = line.split("\\s+")
        currentFile = parts.lastOption.map(_.stripPrefix("b/")).getOrElse("")
      case line if line.startsWith("+++ ") =>
        // Some renames don't emit `diff --git`; refresh from the +++ line.
        currentFile = line.stripPrefix("+++ ").stripPrefix("b/")
      case HunkHeader(oldStart, newStart) =>
        closeHunk()
        currentOld = oldStart.toInt
        currentNew = newStart.toInt
        hunkOpen = true
      case line if hunkOpen =>
        val (kind, text) =
          if (line.startsWith("+")) (GitDiffLineKind.Add, line.drop(1))
          else if (line.startsWith("-")) (GitDiffLineKind.Remove, line.drop(1))
          else (GitDiffLineKind.Context, line.stripPrefix(" "))
        currentLines += GitDiffLine(kind, text)
      case _ => ()
    }
    closeHunk()
    hunks.toList
  }
}
