package sigil.tooling.container

import fabric.io.JsonParser
import fabric.rw.*
import fabric.{Arr, Json, NumInt, Obj, Str}
import rapid.Task
import sigil.TurnContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.{Tool, ToolName}

/**
 * Read a file via the host's filesystem context and materialise
 * its contents as an immutable paginated container. The `itemsId`
 * can be passed to any consumer that takes `itemsId:
 * Id[ToolOutputNode]`.
 *
 * The host's `FileSystemContext` (typically configured by
 * `ToolingSigil.fileSystemContext`) governs sandboxing — the tool
 * accepts whatever paths that context accepts.
 */
final class LoadFileAsContainerTool(fileSystemContext: FileSystemContext) extends Tool {
  type Input  = LoadFileAsContainerInput
  type Output = CreateContainerOutput
  val inputRW  = summon[RW[LoadFileAsContainerInput]]
  val outputRW = summon[RW[CreateContainerOutput]]

  val name = ToolName("load_file_as_container")
  val description =
    """Read a file and split it into a paginated container of items, returning the containerId.
      |Splitting policies:
      |  - `LinesParser`: each non-empty line → one item `{"line": "<text>", "lineNumber": <n>}`.
      |  - `JsonArrayParser`: the whole file parsed as a JSON array; each element → one item.
      |  - `JsonLinesParser`: each non-empty line parsed as one JSON value (JSONL / NDJSON).
      |  - `CsvParser`: first line headers, each subsequent row → one item keyed by header.
      |  - `RegexSplitParser(pattern)`: split on regex; each non-empty segment → one item
      |    `{"segment": "<text>", "index": <n>}`.
      |
      |Pass the returned containerId to any consumer that takes one (e.g. dispatch_workers).""".stripMargin
  override val keywords = Set(
    "container", "load", "read", "file", "import", "csv", "jsonl",
    "lines", "split", "from file"
  )

  override def paginate: Boolean = false

  override def executeOutput(input: LoadFileAsContainerInput,
                             ctx: TurnContext): Task[CreateContainerOutput] =
    WorkspacePathResolver.resolve(ctx, input.filePath).flatMap { resolvedPath =>
      fileSystemContext.readFile(resolvedPath).flatMap { content =>
        val items = parse(content, input.parser)
        ContainerSupport.persistItems(ctx.sigil, ctx.conversation.id, items).map {
          case (itemsId, count) => CreateContainerOutput(itemsId = itemsId, itemCount = count)
        }
      }
    }

  private def parse(content: String, parser: ItemParser): List[Json] = parser match {
    case ItemParser.LinesParser =>
      content.linesIterator.zipWithIndex.collect {
        case (line, idx) if line.trim.nonEmpty =>
          Obj("line" -> Str(line), "lineNumber" -> NumInt(idx + 1))
      }.toList
    case ItemParser.JsonArrayParser =>
      JsonParser(content) match {
        case a: Arr => a.value.toList
        case other  => List(other)
      }
    case ItemParser.JsonLinesParser =>
      content.linesIterator
        .filter(_.trim.nonEmpty)
        .map(JsonParser(_))
        .toList
    case ItemParser.CsvParser =>
      val lines = content.linesIterator.filter(_.nonEmpty).toList
      if (lines.size <= 1) Nil
      else {
        val headers = lines.head.split(",").toList.map(_.trim)
        lines.tail.map { row =>
          val cells = row.split(",", -1).toList.map(_.trim)
          Obj(headers.zip(cells.padTo(headers.size, "")).map { case (k, v) =>
            k -> (Str(v): Json)
          }*)
        }
      }
    case ItemParser.RegexSplitParser(pattern) =>
      scala.util.Try(pattern.r).toOption match {
        case None => Nil
        case Some(rx) =>
          rx.split(content).toList.zipWithIndex.collect {
            case (segment, idx) if segment.trim.nonEmpty =>
              Obj("segment" -> Str(segment), "index" -> NumInt(idx))
          }
      }
  }
}
