package sigil.tool.fs

import fabric.rw.*

/**
 * Tree-shaped grep output. Top-level nodes are [[FileMatch]]
 * (one per file with at least one hit); their children are
 * [[LineMatch]] records for each match in that file.
 *
 * The agent pages through files at the top level (`query_tool_output`
 * against the tool-call's id), then expands a file's matches by
 * calling `query_tool_output` against the file node's id.
 */
sealed trait GrepNode derives RW

object GrepNode {

  /** Top-level node — one per file with at least one match. */
  case class FileMatch(filePath: String, matchCount: Int) extends GrepNode

  /** Child node — one per individual match within a file. */
  case class LineMatch(lineNumber: Int,
                       content: String,
                       contextBefore: List[String] = Nil,
                       contextAfter: List[String] = Nil) extends GrepNode
}
