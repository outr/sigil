package sigil.tool.model

import fabric.rw.*

/**
 * Classified failure reason for [[sigil.tool.git.GitPushTool]]. The
 * tool maps git's stderr signals onto these cases so the agent can
 * react programmatically without parsing raw stderr.
 */
enum GitPushError derives RW {

  /**
   * Force / force-with-lease on a protected branch was attempted
   * without `confirmForcePush = true`. The push never shelled out.
   */
  case ForcePushBlocked

  /**
   * Remote has commits the local doesn't — pull then retry.
   */
  case NonFastForward

  /**
   * Remote rejected the push (branch protection or a server hook).
   */
  case Rejected

  /**
   * No upstream branch — pass `setUpstream = true` on first push.
   */
  case NoUpstream

  /**
   * Authentication failed (ssh key / credential).
   */
  case AuthFailed

  /**
   * Catch-all when no specific signal matched git's stderr.
   */
  case Unknown
}
