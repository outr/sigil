package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.context.ListMemoriesTool]].
 *
 *   - [[ListMemoriesOutput.Listed]] — a page of matching memories
 *     plus its pagination envelope.
 *   - [[ListMemoriesOutput.NoAccessibleSpaces]] — the caller's chain
 *     has no accessible memory space, so there is nothing to list.
 */
enum ListMemoriesOutput extends sigil.tool.ToolOutput derives RW {

  case Listed(memories: List[MemoryListEntry], page: MemoryListPage)

  case NoAccessibleSpaces(note: String)
}
