package sigil.tool

/**
 * Who bounds a tool's rendered output:
 *
 *   - [[FrameworkBounded]] — the framework's overflow path applies: a
 *     result over [[sigil.Sigil.inlineContentThreshold]] is written to
 *     a workspace file and a bounded head + path is inlined.
 *   - [[SelfBounded]] — the tool guarantees it has sized its own
 *     output; the framework delivers it verbatim, never truncated or
 *     filed (e.g. `find_capability` sizes its roster to the model
 *     window and must arrive intact).
 */
enum OutputBounds {
  case FrameworkBounded
  case SelfBounded
}
