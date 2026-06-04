package sigil.tool.model

import fabric.rw.*

/**
 * Listing scope for `process_list`.
 */
enum ProcessListScope derives RW {

  /**
   * Restrict the listing to subprocesses spawned by the calling
   * conversation.
   */
  case Current

  /**
   * Return every registered subprocess handle across all
   * conversations.
   */
  case All
}
