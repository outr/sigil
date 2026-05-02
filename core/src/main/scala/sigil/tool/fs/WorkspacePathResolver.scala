package sigil.tool.fs

import rapid.Task
import sigil.TurnContext

import java.nio.file.Paths

/**
 * Resolves filesystem-tool input paths against the conversation's
 * workspace (per [[sigil.Sigil.workspaceFor]]) so multi-conversation
 * apps can route filesystem ops correctly.
 *
 * Semantics mirror `Path.resolve`:
 *
 *   - **Absolute input** — pass through unchanged. The agent
 *     supplied a fully-qualified path; respect it.
 *   - **Relative input + workspace configured** — resolve against
 *     the workspace. `read_file build.sbt` against a conversation
 *     whose workspace is `/tmp/project-a` becomes
 *     `read_file /tmp/project-a/build.sbt`.
 *   - **Relative input + no workspace** — pass through unchanged.
 *     The downstream [[FileSystemContext]] resolves it against
 *     JVM cwd via `Paths.get(...).toAbsolutePath`. This preserves
 *     legacy single-project behavior for apps that haven't
 *     overridden `workspaceFor`.
 *
 * The result is the path string each tool hands to its
 * [[FileSystemContext]] method; the FS context itself stays
 * conversation-unaware.
 */
object WorkspacePathResolver {

  /** Resolve `path` against the conversation's workspace. */
  def resolve(ctx: TurnContext, path: String): Task[String] = {
    val asPath = Paths.get(path)
    if (asPath.isAbsolute) Task.pure(path)
    else ctx.sigil.workspaceFor(ctx.conversation.id).map {
      case Some(workspace) => workspace.resolve(path).toString
      case None            => path
    }
  }

  /** Resolve an optional path field (e.g. `BashInput.workingDir`).
    * `None` falls through to the configured workspace as cwd when
    * one exists, otherwise stays `None` (the FS context's existing
    * "use JVM cwd" default applies). */
  def resolveOptional(ctx: TurnContext, path: Option[String]): Task[Option[String]] = path match {
    case Some(p) => resolve(ctx, p).map(Some(_))
    case None    => ctx.sigil.workspaceFor(ctx.conversation.id).map(_.map(_.toString))
  }
}
