package sigil.tooling

import org.eclipse.lsp4j.{Range, SymbolInformation, SymbolKind, WorkspaceSymbol}

/**
 * Flat symbol-search hit. Coalesces the legacy `SymbolInformation`
 * (which carries a full `Location`) with the modern `WorkspaceSymbol`
 * (which can carry just `{uri}` and resolve the range lazily) into
 * one shape tools can format without branching.
 *
 * Returned by [[LspSession.workspaceSymbols]] and
 * [[LspSession.documentSymbols]] — anything that resolves to a
 * file:range pair with a name and kind.
 */
final case class SymbolHit(name: String,
                           kind: SymbolKind,
                           containerName: Option[String],
                           uri: String,
                           range: Option[Range])

object SymbolHit {
  @annotation.nowarn("cat=deprecation")
  def fromSymbolInformation(si: SymbolInformation): SymbolHit = {
    val loc = si.getLocation
    SymbolHit(
      name = si.getName,
      kind = si.getKind,
      containerName = Option(si.getContainerName),
      uri = if (loc != null) loc.getUri else "",
      range = Option(loc).map(_.getRange)
    )
  }

  def fromWorkspaceSymbol(ws: WorkspaceSymbol): SymbolHit = {
    val loc = ws.getLocation
    val (uri, range) =
      if (loc.isLeft) (loc.getLeft.getUri, Option(loc.getLeft.getRange))
      else            (loc.getRight.getUri, None)
    SymbolHit(
      name = ws.getName,
      kind = ws.getKind,
      containerName = Option(ws.getContainerName),
      uri = uri,
      range = range
    )
  }
}
