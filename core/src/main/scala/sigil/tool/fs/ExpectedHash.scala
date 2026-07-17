package sigil.tool.fs

/**
 * Sigil #402 — normalize a safe-edit `expectedHash` input to absent when it's
 * a model's way of writing "no hash" rather than a real SHA-256.
 *
 * `expectedHash` is `Option[String]`, but a model that wants "absent" sometimes
 * serializes it as the literal string `"None"` / `"null"` (or an empty /
 * whitespace string). That arrives as a non-empty `Some`, which the safe-edit
 * guard treats as a genuine expected hash — and since the sentinel never equals
 * the file's real hash, every such edit fails `Stale`. Treat these sentinels as
 * absent so the edit commits unconditionally, exactly as the model intended; a
 * real hash still drives the genuine concurrency guard.
 */
object ExpectedHash {

  private val Sentinels: Set[String] = Set("none", "null", "nil", "undefined")

  /**
   * The effective expected hash: `None` for a real-absent or sentinel value,
   * `Some(trimmed)` for a plausible hash.
   */
  def normalize(expectedHash: Option[String]): Option[String] =
    expectedHash.map(_.trim).filter(h => h.nonEmpty && !Sentinels.contains(h.toLowerCase))
}
