package sigil.information

import fabric.rw.{PolyName, PolyType, serialized}
import lightdb.id.Id

/**
 * The fully-resolved content record referenced by an
 * [[InformationSummary]] in a conversation's context catalog. Returned by
 * [[sigil.Sigil.getInformation]] when the LLM invokes
 * [[sigil.tool.core.LookupInformationTool]] against a catalog id.
 *
 * Open `PolyType` hierarchy. Sigil provides no concrete subtypes because
 * content types are domain concepts (articles, invoices, tickets,
 * memories, images — whatever the app has). Apps subclass `Information`
 * for each of their types, register the RWs into the poly, and implement
 * `Sigil.getInformation` to dispatch to the right resolver.
 *
 * The short class name of each registered subtype becomes a valid
 * `PolyName[Information]` automatically — reachable via
 * `Information.name.of(...)` / `.from(...)` / `.registered`.
 */
trait Information {
  def id: Id[Information]

  /**
   * Classifier matching this subtype's registered name. Default uses the
   * runtime class's short name; overrides are rarely needed.
   */
  @serialized
  def informationType: PolyName[Information] = Information.name.of(this)
}

object Information extends PolyType[Information]()(using scala.reflect.ClassTag(classOf[Information]))
