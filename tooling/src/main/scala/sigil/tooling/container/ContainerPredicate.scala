package sigil.tooling.container

import fabric.Json
import fabric.rw.*

/**
 * Predicate evaluated against each row's payload Json when
 * narrowing a container via [[FilterContainerTool]].
 *
 *   - [[Contains]] — substring match against the rendered JSON of
 *     the payload. The simplest filter — cheap and good enough
 *     when the agent wants "rows mentioning X anywhere."
 *   - [[JsonPath]] — dotted path lookup into the payload. With
 *     `equals` set, the value at the path must equal the supplied
 *     JSON value; otherwise the path must merely resolve to a
 *     truthy value (non-null, non-empty string, non-zero number,
 *     non-empty array / object).
 *   - [[RegexMatch]] — regex applied to the stringified value at
 *     the dotted `field` path. Used for finer-grained filtering
 *     than plain substring (e.g. `^src/main/scala/.*\\.scala$`).
 */
sealed trait ContainerPredicate derives RW

object ContainerPredicate {

  /** Substring match against the payload's compact-JSON rendering. */
  case class Contains(text: String) extends ContainerPredicate derives RW

  /** Dotted-path lookup. With `equals = Some(value)`, the path's
    * resolved value must equal `value`. With `equals = None`, the
    * path must merely resolve to a truthy value. */
  case class JsonPath(path: String, equals: Option[Json] = None) extends ContainerPredicate derives RW

  /** Regex applied to the stringified value at the dotted `field`
    * path. */
  case class RegexMatch(field: String, pattern: String) extends ContainerPredicate derives RW
}
