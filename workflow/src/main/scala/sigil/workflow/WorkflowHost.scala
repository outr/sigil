package sigil.workflow

import sigil.Sigil

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide handle giving compiled workflow steps a way to reach
 * back to the host [[Sigil]] without threading it through Strider's
 * engine machinery.
 *
 * Set once by [[WorkflowSigil]] at module init; read by
 * [[SigilJobStep]] and the framework triggers when their
 * `execute` / `register` runs.
 *
 * One Sigil per JVM is the actual constraint (`Sigil.instance` is
 * `.singleton`), so a static reference matches the runtime model.
 * Apps with a multi-tenant Sigil-per-request shape don't pull in
 * `sigil-workflow` as-is — they wrap differently.
 */
object WorkflowHost {
  private val ref: AtomicReference[Sigil] = new AtomicReference(null)

  def set(sigil: Sigil): Unit = ref.set(sigil)

  def get: Sigil = {
    val s = ref.get()
    if (s == null) throw new IllegalStateException(
      "WorkflowHost.get called before WorkflowHost.set. Mix `WorkflowSigil` into your Sigil to wire this automatically."
    )
    s
  }

  def isSet: Boolean = ref.get() != null
}
