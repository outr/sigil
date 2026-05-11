# ❌ #135 — No `git_push` tool; agent can commit but can't push the work

**Where:**
- `core/src/main/scala/sigil/tool/git/` — git tool family. Current members:
  - `GitDiffTool.scala` (diff)
  - `GitStatusTool.scala` (status)
  - `GitCommitTool.scala` (commit)
  - `GitLogTool.scala` (log)
  - `GitBranchTool.scala` (branch operations)
  - **Missing**: `GitPushTool.scala`

**What's wrong:**

The git tool family covers the local end-to-end (status → diff → branch → commit) but stops at commit. There is no tool to push committed changes to a remote. An agent finishing a "implement feature X, commit, and push to a branch" task with the current roster has to either:

- Fall through to `bash` and shell out to `git push` (loses typed I/O, error handling, structured outcome — the same reason a dedicated `git_commit` tool exists rather than agents shelling)
- OR stop after `git_commit` and ask the user to push manually

Sage's user workflow is the canonical case: single-user-local, commits land via the agent, but the push step requires dropping out of the agent loop entirely. With Sage running as `git_commit`'s opt-in caller (per `Sage.scala:333` — *"Single-user-local: the human ran the agent and authorised its writes implicitly, so commit-authorship is on"*), the same authorisation argument applies to push.

**Test first:**

```scala
class GitPushToolSpec extends AsyncWordSpec with Matchers {
  "git_push" should {
    "push committed changes to the configured remote" in {
      // Fixture: a git repo with one commit ahead of origin/main.
      // Drive GitPushTool with default input (current branch → origin).
      // Assert origin/main now points at the same SHA as HEAD.
    }

    "support targeted remote / branch arguments" in {
      // GitPushInput(remote = Some("upstream"), branch = Some("feature/x")).
      // Assert the push hit `upstream/feature/x` specifically.
    }

    "support `setUpstream` for first-push-of-a-new-branch" in {
      // Fresh local branch with no upstream. Default push fails;
      // GitPushInput(setUpstream = true) should run
      // `git push --set-upstream <remote> <branch>` and succeed.
    }

    "return a structured Failure with the remote's rejection reason on non-fast-forward" in {
      // Setup: remote has commits the local branch doesn't.
      // Drive push without `force`. Assert ToolResult.Failure with
      // `hint` mentioning `git_pull` or fast-forward, NOT a raw stderr
      // dump.
    }

    "refuse force-push to protected branch names by default" in {
      // GitPushInput(branch = Some("main"), force = true).
      // Without an explicit `confirmForcePush = true` (or app-level
      // override), assert Failure with guidance. Force-pushing main
      // is the destructive default-deny case.
    }
  }
}
```

All five must fail on current `main` (the type doesn't exist yet).

**Suggested fix:**

Mirror `GitCommitTool` in opt-in semantics — ship in `core` but require apps to register it via `staticTools` (so multi-user / hosted Sigil consumers don't accidentally surface a push capability). Single-user-local apps (Sage) opt in alongside `GitCommitTool`.

```scala
package sigil.tool.git

import fabric.{bool, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, FsToolEmit, WorkspacePathResolver}
import sigil.tool.{ToolExample, ToolName, TypedTool, ToolInput}

final case class GitPushInput(
  workingDir:        Option[String] = None,
  remote:            Option[String] = None,           // default: tracked upstream of current branch
  branch:            Option[String] = None,           // default: HEAD's current branch
  setUpstream:       Boolean        = false,          // -u flag
  force:             Boolean        = false,          // --force
  forceWithLease:    Boolean        = false,          // --force-with-lease (safer force)
  confirmForcePush:  Boolean        = false,          // opt-in for force-pushing protected branches
  tags:              Boolean        = false           // --tags
) extends ToolInput derives RW

final class GitPushTool(context: FileSystemContext)
  extends TypedTool[GitPushInput](
    name = ToolName("git_push"),
    description =
      """Push committed changes to a remote. Defaults push the current branch to its tracked upstream;
        |pass `remote` / `branch` for explicit targets. Use `setUpstream` on a new branch's first push.
        |
        |Force-push is gated: `force` / `forceWithLease` on a protected branch (main, master, develop)
        |require `confirmForcePush = true`. Prefer `forceWithLease` over `force` — it refuses to clobber
        |upstream commits you haven't seen.
        |
        |Returns push outcome: pushed-refs, new upstream tracking (if `setUpstream`), and on failure a
        |structured reason (`{error: "non-fast-forward", hint: "git_pull and retry"}` etc.) instead of
        |raw stderr.""".stripMargin,
    examples = List(
      ToolExample("Push current branch to its upstream",  GitPushInput()),
      ToolExample("First push of a feature branch",       GitPushInput(setUpstream = true)),
      ToolExample("Push tags too",                        GitPushInput(tags = true)),
      ToolExample("Force-with-lease (safer force)",       GitPushInput(forceWithLease = true))
    ),
    keywords = Set("git", "push", "publish", "upload", "remote", "upstream", "deploy", "sync")
  ) {
  override val destructive: Boolean = true
  override val openWorld:   Boolean = true   // touches a network remote

  override protected def executeTyped(input: GitPushInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      // Build args defensively — force-push gating first, then construct cmd.
      val gateError = validateForcePushGate(input)
      gateError match {
        case Some(reason) =>
          Stream.emit[Event](FsToolEmit(obj("error" -> str(reason)), ctx))
        case None =>
          val flags = List(
            if (input.setUpstream)    Some("--set-upstream") else None,
            if (input.force)          Some("--force") else None,
            if (input.forceWithLease) Some("--force-with-lease") else None,
            if (input.tags)           Some("--tags") else None
          ).flatten.mkString(" ")
          val targetArgs = (input.remote, input.branch) match {
            case (Some(r), Some(b)) => s" $r $b"
            case (Some(r), None)    => s" $r"
            case (None, Some(b))    => s" origin $b"  // explicit branch needs an explicit remote
            case (None, None)       => ""
          }
          val cmd = s"git push${if (flags.isEmpty) "" else " " + flags}$targetArgs"
          context.executeCommand(cmd, dir).map { r =>
            val payload =
              if (r.exitCode != 0)
                obj(
                  "error"    -> str(classifyPushError(r.stderr)),
                  "exitCode" -> num(r.exitCode),
                  "stderr"   -> str(r.stderr)
                )
              else
                obj(
                  "pushed" -> bool(true),
                  "output" -> str(r.stdout),
                  "stderr" -> str(r.stderr)  // git push reports progress on stderr even on success
                )
            Stream.emit[Event](FsToolEmit(payload, ctx))
          }
      }
    }
  )

  /** Protected-branch gating: force / force-with-lease on main /
    * master / develop without `confirmForcePush = true` returns a
    * structured error. Apps can override the protected list via a
    * config-driven sigil-side helper. */
  private def validateForcePushGate(input: GitPushInput): Option[String] = {
    val protectedBranches = Set("main", "master", "develop")
    val isProtected = input.branch.exists(protectedBranches.contains)
    val isForcing   = input.force || input.forceWithLease
    if (isProtected && isForcing && !input.confirmForcePush)
      Some(s"Refusing to force-push protected branch '${input.branch.get}' without confirmForcePush = true. " +
           s"Set confirmForcePush = true to override, or push a non-protected branch.")
    else None
  }

  /** Map git's stderr signals to a structured `error` string the
    * agent can react to programmatically. Falls through to "push
    * failed" with raw stderr in the payload when no specific signal
    * matches. */
  private def classifyPushError(stderr: String): String = stderr match {
    case s if s.contains("non-fast-forward")        => "non-fast-forward (remote has commits you don't; run git_pull then retry)"
    case s if s.contains("rejected")                => "remote rejected the push (likely branch protection or hook)"
    case s if s.contains("does not exist") &&
              s.contains("upstream")                => "no upstream branch (pass setUpstream = true on first push)"
    case s if s.contains("Permission denied") ||
              s.contains("authentication")          => "authentication failed (ssh key / credential)"
    case _                                          => "push failed"
  }
}
```

**Sage-side opt-in** (single-user-local, parallel to existing `GitCommitTool` registration in `Sage.scala:333`):

```scala
new GitCommitTool(LocalFileSystemContext()),
new GitPushTool(LocalFileSystemContext()),
```

**Companion notes:**

- **Depends on #134**: `FsToolEmit` returning a `Message` rather than `ToolResults` means git_push's output renders as a separate JSON-blob bubble too. The tool works in isolation; the rendering is broken downstream of the `FsToolEmit` fix. Land #134 first or land both together.
- **No `git_pull` is also missing**, but that's a separate bug — pull involves merge-conflict handling, fast-forward decisions, and rebase-vs-merge policy that a focused "push" tool doesn't need. Worth filing as a follow-up if Sage's workflow needs it.
- **Force-push gating is opinionated**: the default-deny on protected branches is intentional. Apps that need to override (release tooling, branch-history rewrites) can either pass `confirmForcePush = true` per call OR provide their own subclass that overrides `validateForcePushGate` to inject domain-specific rules (e.g. "force-push only on release-N.M branches that match a regex").
