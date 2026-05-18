package sigil.tool.git

import fabric.{bool, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, FsToolEmit, WorkspacePathResolver}
import sigil.tool.model.GitPushInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * `git_push` — push committed changes to a remote (sigil bug #135).
 * WRITES external network state; apps gate this tool the same way
 * they gate `git_commit` — opt-in via `staticTools`, not in
 * `AllShippedTools`.
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
 * Returns `{pushed, output, stderr}` on success or
 * `{error, exitCode, stderr}` on failure. `error` is a classified
 * string the agent can pattern-match on:
 *
 *   - `"non-fast-forward (remote has commits you don't; …)"` — pull then retry
 *   - `"remote rejected the push (likely branch protection or hook)"`
 *   - `"no upstream branch (pass setUpstream = true on first push)"`
 *   - `"authentication failed (ssh key / credential)"`
 *   - `"push failed"` (catch-all when no specific signal matches)
 */
final class GitPushTool(context: FileSystemContext)
  extends TypedTool[GitPushInput](
    name = ToolName("git_push"),
    description =
      """Push committed changes to a remote. Defaults push the current branch to its tracked
        |upstream; pass `remote` / `branch` for explicit targets, `setUpstream` on a new
        |branch's first push, `forceWithLease` for safer force-pushes.
        |
        |Force-push is gated: `force` / `forceWithLease` on a protected branch (main, master,
        |develop) requires `confirmForcePush = true`. Prefer `forceWithLease` over `force` —
        |it refuses to clobber upstream commits you haven't seen.
        |
        |Returns the push outcome: `{pushed, output, stderr}` on success, or a structured
        |`{error, exitCode, stderr}` on failure. The `error` string is classified (non-fast-
        |forward / rejected / no upstream / auth-failed / catch-all) so the agent can react
        |programmatically without parsing raw stderr.""".stripMargin,
    examples = List(
      ToolExample("Push current branch to its upstream", GitPushInput()),
      ToolExample("First push of a feature branch", GitPushInput(setUpstream = true)),
      ToolExample("Push tags too", GitPushInput(tags = true)),
      ToolExample("Force-with-lease (safer force)", GitPushInput(forceWithLease = true)),
      ToolExample("Explicit remote and branch", GitPushInput(remote = Some("upstream"), branch = Some("feature/x")))
    ),
    keywords = Set("git", "push", "publish", "upload", "remote", "upstream", "deploy", "sync")
  )
  with sigil.tool.DestructiveExternalTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: GitPushInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      validateForcePushGate(input) match {
        case Some(reason) =>
          rapid.Task.pure(Stream.emit[Event](FsToolEmit(obj("error" -> str(reason)), ctx)))
        case None =>
          val flagsParts = List(
            if (input.setUpstream) Some("--set-upstream") else None,
            if (input.force) Some("--force") else None,
            if (input.forceWithLease) Some("--force-with-lease") else None,
            if (input.tags) Some("--tags") else None
          ).flatten
          val flagsStr = if (flagsParts.isEmpty) "" else " " + flagsParts.mkString(" ")
          // `--set-upstream` REQUIRES an explicit `<remote> <branch>`
          // pair on the command line (git refuses with "no upstream
          // branch" otherwise). When the caller didn't pass them
          // explicitly, fall back to `origin` + the current branch
          // resolved from HEAD.
          val branchTask: rapid.Task[Option[String]] = input.branch match {
            case some @ Some(_) => rapid.Task.pure(some)
            case None if input.setUpstream =>
              context.executeCommand("git rev-parse --abbrev-ref HEAD", dir).map { r =>
                if (r.exitCode == 0) Some(r.stdout.trim).filter(_.nonEmpty) else None
              }
            case None => rapid.Task.pure(None)
          }
          val targetArgsTask: rapid.Task[String] = branchTask.map { branchOpt =>
            (input.remote, branchOpt) match {
              case (Some(r), Some(b)) => s" $r $b"
              case (Some(r), None) => s" $r"
              case (None, Some(b)) => s" origin $b" // explicit branch needs an explicit remote
              case (None, None) => ""
            }
          }
          // Materialize the command lazily so `branchTask` runs first.
          targetArgsTask.flatMap { targetArgs =>
            val cmd = s"git push$flagsStr$targetArgs"
            context.executeCommand(cmd, dir).map { r =>
              val payload =
                if (r.exitCode != 0)
                  obj(
                    "error" -> str(classifyPushError(r.stderr)),
                    "exitCode" -> num(r.exitCode),
                    "stderr" -> str(r.stderr)
                  )
                else
                  // git reports progress on stderr even on success — surface it
                  // so the agent can see what was pushed (refs updated, etc.).
                  obj(
                    "pushed" -> bool(true),
                    "output" -> str(r.stdout),
                    "stderr" -> str(r.stderr)
                  )
              Stream.emit[Event](FsToolEmit(payload, ctx))
            }
          }
      }
    }
  )

  /**
   * Protected-branch gating. Force / force-with-lease on main /
   * master / develop without `confirmForcePush = true` returns a
   * structured error. Apps override by subclassing and replacing
   * this method (e.g. allow force on `release/...` branches).
   */
  protected def validateForcePushGate(input: GitPushInput): Option[String] = {
    val protectedBranches = Set("main", "master", "develop")
    val isProtected = input.branch.exists(protectedBranches.contains)
    val isForcing = input.force || input.forceWithLease
    if (isProtected && isForcing && !input.confirmForcePush)
      Some(s"Refusing to force-push protected branch '${input.branch.get}' without confirmForcePush = true. " +
        "Set confirmForcePush = true to override, or push a non-protected branch.")
    else None
  }

  /**
   * Map git's stderr signals onto a structured `error` string the
   * agent can react to programmatically. Falls through to "push
   * failed" with raw stderr in the payload when no specific signal
   * matches.
   */
  private def classifyPushError(stderr: String): String = stderr match {
    case s if s.contains("non-fast-forward") => "non-fast-forward (remote has commits you don't; run git_pull then retry)"
    case s if s.contains("rejected") => "remote rejected the push (likely branch protection or hook)"
    case s
        if s.contains("does not exist") &&
          s.contains("upstream") => "no upstream branch (pass setUpstream = true on first push)"
    case s if s.contains("no upstream branch") => "no upstream branch (pass setUpstream = true on first push)"
    case s
        if s.contains("Permission denied") ||
          s.contains("authentication") => "authentication failed (ssh key / credential)"
    case _ => "push failed"
  }
}
