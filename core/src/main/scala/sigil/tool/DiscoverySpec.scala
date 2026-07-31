package sigil.tool

import lightdb.id.Id
import sigil.{GlobalSpace, SpaceId}
import sigil.provider.Mode

/**
 * How a tool surfaces in discovery (`find_capability`) and roster
 * ranking:
 *
 *   - `keywords` — the curated search surface; required non-empty for
 *     discoverable kinds (see [[ToolKind.discoverable]]).
 *   - `space` — the single [[SpaceId]] the tool is visible under
 *     (single-assignment: copy the record to surface it elsewhere).
 *   - `modes` — mode discriminators the tool is discoverable in;
 *     empty means universally discoverable.
 *   - `preferIfNoBetter` — ranker demotion for generic primitives
 *     that should lose to domain-specific matches.
 *   - `toolchain` — contextual ranker boost when the named toolchain
 *     is active for the conversation.
 *   - `suggestedNextTools` — names appended to the caller's
 *     `suggestedTools` overlay when this tool runs.
 *   - `kind` — categorical discriminator for client-side filtering
 *     and discoverability classification.
 */
final case class DiscoverySpec(keywords: Set[String] = Set.empty,
                               space: SpaceId = GlobalSpace,
                               modes: Set[Id[Mode]] = Set.empty,
                               preferIfNoBetter: Boolean = false,
                               toolchain: Option[String] = None,
                               suggestedNextTools: List[ToolName] = Nil,
                               kind: ToolKind = BuiltinKind)

object DiscoverySpec {
  val default: DiscoverySpec = DiscoverySpec()
}
