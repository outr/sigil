package sigil.render

/**
 * Shared formatting helpers for the framework's [[ContentRenderer]]
 * implementations. Markup-agnostic — each renderer wraps these
 * plain-string results in its own markup.
 */
object RenderUtil {

  /**
   * Render a byte count as a human-readable size string (`B` / `KB`
   * / `MB` / `GB`, one decimal place above the byte threshold).
   * Used by every renderer to label file blocks.
   */
  def formatSize(bytes: Long): String =
    if (bytes < 1024) s"$bytes B"
    else if (bytes < 1024 * 1024) f"${bytes / 1024.0}%.1f KB"
    else if (bytes < 1024L * 1024 * 1024) f"${bytes / (1024.0 * 1024)}%.1f MB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.1f GB"
}
