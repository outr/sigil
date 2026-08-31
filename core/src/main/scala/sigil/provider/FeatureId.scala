package sigil.provider

import fabric.rw.*

/**
 * Identity of a [[ContextFeature]].
 *
 * Open by design, unlike the closed [[sigil.diagnostics.ProfileSection]]
 * enum it attributes into: features ship from the framework, from apps,
 * and from third-party modules that install their tools and their
 * context together, so no single enum can enumerate them. The id is the
 * handle apps disable a feature by ([[sigil.Sigil.disabledFeatures]])
 * and the key the profiler reports its tokens under.
 *
 * Opaque over `String`: no runtime cost, and the only way to build one
 * is [[apply]], so a bare string can't drift in as an id.
 */
opaque type FeatureId = String

object FeatureId {

  /**
   * A feature id. Blank ids are rejected — an unnameable feature can
   * neither be disabled nor attributed.
   */
  def apply(value: String): FeatureId = {
    require(value.trim.nonEmpty, "FeatureId cannot be blank — the id is how a feature is disabled and profiled.")
    value
  }

  extension (id: FeatureId) {
    def value: String = id
  }

  given rw: RW[FeatureId] =
    RW.string[FeatureId](asString = id => id, fromString = s => s, className = Some("sigil.provider.FeatureId"))
}
