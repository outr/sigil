package sigil.tool

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.Sigil
import sigil.participant.ParticipantId
import sigil.provider.Mode

/**
 * Base for tools that wrap another [[Tool]] — everything forwards from
 * ONE value ([[underlying]]) by construction: the spec (capabilities —
 * effect, gates, execution, discovery), the [[ToolIO]] (codecs,
 * schema, examples), identity, and record metadata. A decorator
 * overrides ONLY `resolve` (how the body runs); a capability added to
 * [[ToolProfile]] reaches every decorator automatically, so the
 * "wrapped tool silently loses its consent gate / destructive warning /
 * detachability" class is unrepresentable.
 *
 * A decorator that deliberately diverges (a proxy that cannot be
 * detachable because its transport can't stream) overrides `spec`
 * explicitly — the divergence is visible in the diff, never an
 * accident of forgetting to forward a flag.
 */
trait ToolDecorator extends Tool {

  /** The decorated tool — the single source every surface forwards
    * from. Supply as a constructor parameter (`class MyDecorator(val
    * underlying: Tool, …) extends ToolDecorator`). [[spec]] is `lazy`
    * so a subclass that initializes `underlying` in its own body
    * (rather than as a parameter) still reads a constructed value. */
  val underlying: Tool

  type Input = underlying.Input
  type Output = underlying.Output

  lazy val spec: ToolSpec = underlying.spec

  def io: ToolIO[Input, Output] = underlying.io

  override def createdBy: Option[ParticipantId] = underlying.createdBy
  override def _id: Id[Tool] = underlying._id
  override def created: Timestamp = underlying.created
  override def modified: Timestamp = underlying.modified

  override def verification: Boolean = underlying.verification
  override def externalizableInputFields: Set[String] = underlying.externalizableInputFields
  override def wireDescription(mode: Mode, sigil: Sigil): String = underlying.wireDescription(mode, sigil)
  override def descriptionFor(mode: Mode, sigil: Sigil): String = underlying.descriptionFor(mode, sigil)
  override def summarize(output: Output, jsonRendered: String): String = underlying.summarize(output, jsonRendered)
}
