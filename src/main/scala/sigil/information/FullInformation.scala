package sigil.information

import fabric.rw.serialized
import lightdb.id.Id
import sigil.{PolyName, PolyType}

/**
 * The fully-resolved record for an [[Information]] catalog entry —
 * returned by [[sigil.Sigil.getInformation]] when the LLM's lookup tool
 * asks for the content behind a catalog id.
 *
 * Open `PolyType` hierarchy. Sigil provides no concrete subtypes because
 * content types are domain concepts (articles, invoices, tickets,
 * memories, images, whatever the app has). Apps subclass `FullInformation`
 * for each of their types, register the RWs into the poly, and implement
 * `Sigil.getInformation` to dispatch to the right resolver.
 *
 * The short class name of each registered subtype becomes a valid
 * `PolyName[FullInformation]` automatically — reachable via
 * `FullInformation.name.of(...)` / `.from(...)` / `.registered`.
 */
trait FullInformation {
  def id: Id[Information]

  /**
   * Classifier matching this subtype's registered name. Default uses the
   * runtime class's short name; overrides are rarely needed.
   */
  @serialized
  def informationType: PolyName[FullInformation] = FullInformation.name.of(this)
}

object FullInformation extends PolyType[FullInformation]
