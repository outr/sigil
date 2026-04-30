package sigil.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Stable, short identifier derived from a workspace path. Used as
 * the [[sigil.mcp.McpServerConfig.name]] for the Metals connection
 * — `metals-<12 hex chars of SHA-256>`.
 *
 * The path is canonicalized (absolute + normalized) before hashing
 * so different relative spellings of the same workspace produce
 * the same key. Hash truncation is 12 chars — enough to make
 * collisions astronomically unlikely across the small set of
 * workspaces a single Sigil typically runs.
 */
object MetalsWorkspaceKey {
  def of(workspace: Path): String = {
    val canonical = workspace.toAbsolutePath.normalize.toString
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
    val hex = digest.iterator.take(6).map(b => f"$b%02x").mkString
    s"metals-$hex"
  }
}
