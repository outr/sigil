package sigil.tool.git

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.model.{GitPushError, GitPushInput, GitPushOutput}
import sigil.tool.{Tool, ToolExample, ToolName}

/**
 * `git_push` — push committed changes to a remote. WRITES external
 * network state; apps gate this tool the same way they gate
 * `git_commit` — opt-in via `staticTools`, not in `AllShippedTools`.
 *
 * Defaults push the current branch to its tracked upstream. Pass
 * `remote` / `branch` for explicit targets, `setUpstream` on a new
 * branch's first push, `forceWithLease` for safer force-pushes (it
 * refuses to clobber upstream commits the local hasn't seen).
 *
 * Force-push gating: `force` or `forceWithLease` on a protected
 * branch (main / master / develop) requires `confirmForcePush =
 * true`. Default-deny — apps that need to override (release tooling,
 * branch-history rewrites) pass `confirmForcePush = true` per call
 * or subclass and override [[validateForcePushGate]].
 *
 * Returns a typed [[GitPushOutput]] — `Pushed` on success, `Failed`
 * with a classified [[GitPushError]] otherwise so the agent can
 * pattern-match without parsing raw stderr.
 */
final class GitPushTool(context: FileSystemContext)
  extends Tool with sigil.tool.DestructiveExternalTool {
  type Input  = GitPushInput
  type Output = GitPushOutput
  val inputRW  = summon[RW[GitPushInput]]
  val outputRW = summon[RW[GitPushOutput]]
  val name = ToolName("git_push")
  val description =
    """Push committed changes to a remote. Defaults push the current branch to its tracked
      |upstream; pass `remote` / `branch` for explicit targets, `setUpstream` on a new
      |branch's first push, `forceWithLease` for safer force-pushes.
      |
      |Force-push is gated: `force` / `forceWithLease` on a protected branch (main, master,
      |develop) requires `confirmForcePush = true`. Prefer `forceWithLease` over `force` —
      |it refuses to clobber upstream commits you haven't seen.
      |
      |Returns the push outcome — `Pushed` on success, or `Failed` with a classified error
      |(non-fast-forward / rejected / no upstream / auth-failed / force-push-blocked /
      |unknown) so the agent can react programmatically without parsing raw stderr.""".stripMargin
  override val examples = List(
    ToolExample("Push current branch to its upstream", GitPushInput()),
    ToolExample("First push of a feature branch",      GitPushInput(setUpstream = true)),
    ToolExample("Push tags too",                       GitPushInput(tags = true)),
    ToolExample("Force-with-lease (safer force)",      GitPushInput(forceWithLease = true)),
    ToolExample("Explicit remote and branch",          GitPushInput(remote = Some("upstream"), branch = Some("feature/x")))
  )
  override val keywords = Set("git", "push", "publish", "upload", "remote", "upstream", "deploy", "sync")

  override def executeOutput(input: GitPushInput, ctx: ToolContext): Task[GitPushOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      validateForcePushGate(input) match {
        case Some(reason) =>
          Task.pure(GitPushOutput.Failed(error = GitPushError.ForcePushBlocked, detail = reason))
        case None =>
          val flagsParts = List(
            if (input.setUpstream)    Some("--set-upstream") else None,
            if (input.force)          Some("--force") else None,
            if (input.forceWithLease) Some("--force-with-lease") else None,
            if (input.tags)           Some("--tags") else None
          ).flatten
          val flagsStr = if (flagsParts.isEmpty) "" else " " + flagsParts.mkString(" ")
          // `--set-upstream` REQUIRES an explicit `<remote> <branch>`
          // pair on the command line (git refuses with "no upstream
          // branch" otherwise). When the caller didn't pass them
          // explicitly, fall back to `origin` + the current branch
          // resolved from HEAD.
          val branchTask: Task[Option[String]] = input.branch match {
            case some @ Some(_) => Task.pure(some)
            case None if input.setUpstream =>
              context.executeCommand("git rev-parse --abbrev-ref HEAD", dir).map { r =>
                if (r.exitCode == 0) Some(r.stdout.trim).filter(_.nonEmpty) else None
              }
            case None => Task.pure(None)
          }
          val targetArgsTask: Task[String] = branchTask.map { branchOpt =>
            (input.remote, branchOpt) match {
              case (Some(r), Some(b)) => s" $r $b"
              case (Some(r), None)    => s" $r"
              case (None, Some(b))    => s" origin $b" // explicit branch needs an explicit remote
              case (None, None)       => ""
            }
          }
          // Materialize the command lazily so `branchTask` runs first.
          targetArgsTask.flatMap { targetArgs =>
            val cmd = s"git push$flagsStr$targetArgs"
            context.executeCommand(cmd, dir).map { r =>
              if (r.exitCode != 0) {
                val (error, detail) = classifyPushError(r.stderr)
                GitPushOutput.Failed(
                  error    = error,
                  detail   = detail,
                  exitCode = Some(r.exitCode),
                  stderr   = Some(r.stderr)
                )
              } else
                // git reports progress on stderr even on success — surface it
                // so the agent can see what was pushed (refs updated, etc.).
                GitPushOutput.Pushed(output = r.stdout, stderr = r.stderr)
            }
          }
      }
    }

  /** Protected-branch gating. Force / force-with-lease on main /
    * master / develop without `confirmForcePush = true` returns a
    * refusal reason. Apps override by subclassing and replacing this
    * method (e.g. allow force on `release/...` branches). */
  protected def validateForcePushGate(input: GitPushInput): Option[String] = {
    val protectedBranches = Set("main", "master", "develop")
    val isProtected = input.branch.exists(protectedBranches.contains)
    val isForcing   = input.force || input.forceWithLease
    if (isProtected && isForcing && !input.confirmForcePush)
      Some(s"Refusing to force-push protected branch '${input.branch.get}' without confirmForcePush = true. " +
           "Set confirmForcePush = true to override, or push a non-protected branch.")
    else None
  }

  /** Map git's stderr signals onto a classified [[GitPushError]] plus
    * a human-readable detail string. Falls through to
    * [[GitPushError.Unknown]] when no specific signal matches. */
  private def classifyPushError(stderr: String): (GitPushError, String) = stderr match {
    case s if s.contains("non-fast-forward") =>
      GitPushError.NonFastForward -> "non-fast-forward (remote has commits you don't; run git_pull then retry)"
    case s if s.contains("rejected") =>
      GitPushError.Rejected -> "remote rejected the push (likely branch protection or hook)"
    case s if s.contains("does not exist") && s.contains("upstream") =>
      GitPushError.NoUpstream -> "no upstream branch (pass setUpstream = true on first push)"
    case s if s.contains("no upstream branch") =>
      GitPushError.NoUpstream -> "no upstream branch (pass setUpstream = true on first push)"
    case s if s.contains("Permission denied") || s.contains("authentication") =>
      GitPushError.AuthFailed -> "authentication failed (ssh key / credential)"
    case _ =>
      GitPushError.Unknown -> "push failed"
  }
}
