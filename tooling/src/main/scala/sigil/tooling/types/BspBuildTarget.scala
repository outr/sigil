package sigil.tooling.types

import ch.epfl.scala.bsp4j.BuildTarget
import fabric.rw.*
import scala.jdk.CollectionConverters.*

/**
 * Sigil-flavored mirror of bsp4j's `BuildTarget`. Covers the
 * advertised capabilities (`compile`, `test`, `run`, `debug`) and
 * language tags so the agent can pick a target by capability.
 */
case class BspBuildTarget(uri: String,
                          displayName: Option[String],
                          languages: List[String],
                          canCompile: Boolean,
                          canTest: Boolean,
                          canRun: Boolean,
                          canDebug: Boolean)
  derives RW

object BspBuildTarget {
  def fromBsp4j(t: BuildTarget): BspBuildTarget = {
    val caps = Option(t.getCapabilities)
    BspBuildTarget(
      uri = t.getId.getUri,
      displayName = Option(t.getDisplayName),
      languages = Option(t.getLanguageIds).map(_.asScala.toList).getOrElse(Nil),
      canCompile = caps.exists(_.getCanCompile),
      canTest = caps.exists(_.getCanTest),
      canRun = caps.exists(_.getCanRun),
      canDebug = caps.exists(_.getCanDebug)
    )
  }
}
