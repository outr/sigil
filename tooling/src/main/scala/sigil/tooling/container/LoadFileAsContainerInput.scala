package sigil.tooling.container

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[LoadFileAsContainerTool]] — read a file from the
 * filesystem and materialise its contents as a container.
 *
 *   - `filePath` — path to the file, resolved via the host's
 *     `WorkspacePathResolver`.
 *   - `parser`   — how to split the file into items (lines, JSON
 *     array, JSONL, CSV, or regex split). See [[ItemParser]].
 */
case class LoadFileAsContainerInput(filePath: String,
                                    parser: ItemParser = ItemParser.LinesParser)
  extends ToolInput derives RW
