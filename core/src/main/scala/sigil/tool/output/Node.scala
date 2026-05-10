package sigil.tool.output

import fabric.rw.*

/**
 * One node in a tool's paginated output tree. Tools emit a
 * `rapid.Stream[Node[A]]` of top-level nodes from
 * [[PaginatedTool.executeStream]]; the framework drains the stream
 * into [[ToolOutputNode]] rows keyed by
 * `(conversationId, callId, referenceId, ordinal)` so the agent
 * can navigate the result via `next_page` / `query_tool_output`
 * without ever materialising the full result in memory.
 *
 *   - `payload` — the typed per-item value (e.g. `GrepFileMatch`,
 *     `GlobEntry`, `BashOutputLine`).
 *   - `hasChildren` — whether this node has expandable child rows.
 *     Top-level nodes that wrap "this file has N matches" set
 *     `hasChildren = true`; children (e.g. per-match line records)
 *     typically set `false`. When `true`, the agent can call
 *     `next_page` against this node's id and receive its children.
 *   - `children` — child stream materialised on demand. The
 *     framework drains it lazily when the agent requests
 *     expansion. Empty when `hasChildren = false`.
 *
 * The recursion is shallow in practice: most tools produce 1-2
 * level trees (top-level + per-item children). Deeper nesting
 * works the same way — children's `children` are simply more
 * deferred streams.
 */
final case class Node[A](payload: A,
                         hasChildren: Boolean = false,
                         children: rapid.Stream[Node[A]] = rapid.Stream.empty[Node[A]])

object Node {

  /** Convenience: a leaf node (no children). */
  def leaf[A](payload: A): Node[A] = Node(payload, hasChildren = false)

  /** Convenience: a parent node that ships its children inline.
    * The framework drains the child stream lazily; tool authors
    * can compose this with `rapid.Stream.fromIterable(seq)` to
    * declare a parent whose children are known in memory. */
  def parent[A](payload: A, children: rapid.Stream[Node[A]]): Node[A] =
    Node(payload, hasChildren = true, children = children)
}
