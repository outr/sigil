package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `git_push` — push committed changes to a remote. WRITES
 * (external network state); apps gate this tool the same way they
 * gate `git_commit` — typically opt-in for single-user / authoring
 * agents.
 *
 * Defaults push the current branch to its tracked upstream. Pass
 * `remote` / `branch` for explicit targets, `setUpstream` on a new
 * branch's first push, `forceWithLease` for safer force operations.
 *
 * Force-pushes to protected branches (main / master / develop)
 * require `confirmForcePush = true` — default-deny.
 */
case class GitPushInput(workingDir: Option[String] = None,
                        remote: Option[String] = None,
                        branch: Option[String] = None,
                        setUpstream: Boolean = false,
                        force: Boolean = false,
                        forceWithLease: Boolean = false,
                        confirmForcePush: Boolean = false,
                        tags: Boolean = false)
  extends ToolInput derives RW
