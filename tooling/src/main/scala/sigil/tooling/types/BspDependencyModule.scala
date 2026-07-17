package sigil.tooling.types

import fabric.rw.*

/**
 * Library coordinate as the build server reports it. Most builds
 * surface `name` as `org:artifact` and `version` as the resolved
 * version string.
 */
case class BspDependencyModule(name: String, version: String) derives RW
