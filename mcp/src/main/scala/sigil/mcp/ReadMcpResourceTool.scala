package sigil.mcp

import fabric.Json
import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.Task
import sigil.GlobalSpace
import sigil.tool.ToolContext
import sigil.tool.{ImageToolOutput, TextToolOutput, Tool, ToolInput, ToolName, ToolOutput, ToolResult}

import java.util.Base64

case class ReadMcpResourceInput(server: String, uri: String) extends ToolInput derives RW

/**
 * Read a resource from a registered MCP server by URI.
 *
 * Text resource contents render to a [[TextToolOutput]]; a resource
 * content carrying a base64 `blob` with an `image/…` `mimeType` is
 * decoded, persisted via [[sigil.Sigil.storeBytes]], and surfaced as
 * an [[ImageToolOutput]] so the framework lifts the picture into the
 * agent's visual context.
 */
final class ReadMcpResourceTool(manager: McpManager) extends Tool {
  type Input  = ReadMcpResourceInput
  type Output = ToolOutput
  val inputRW  = summon[RW[ReadMcpResourceInput]]
  val outputRW = summon[RW[ToolOutput]]

  val name = ToolName("read_mcp_resource")
  val description =
    """Fetch the contents of a resource from a registered MCP server. The server identifies the resource by URI;
      |use list_mcp_servers + (server-specific) discovery to learn what URIs are available.""".stripMargin

  override def executeResult(input: ReadMcpResourceInput, context: ToolContext): Task[ToolResult[ToolOutput]] =
    manager.readResource(input.server, input.uri).flatMap { result =>
      buildOutput(result, input, context).map(ToolResult.Success(_))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Read resource failed: ${e.getMessage}"))
    }

  private def buildOutput(result: Json, input: ReadMcpResourceInput, context: ToolContext): Task[ToolOutput] = {
    val contents = result.get("contents").map(_.asVector.toList).getOrElse(Nil)
    val images   = contents.flatMap(imageRef)
    images match {
      case Nil => Task.pure(TextToolOutput(JsonFormatter.Default(result)))
      case (blob, mime) :: _ =>
        context.sigil.storeBytes(GlobalSpace, Base64.getDecoder.decode(blob), mime,
          metadata = Map("kind" -> "mcp-resource", "server" -> input.server, "uri" -> input.uri))
          .map { stored =>
            val text = contents.flatMap(contentText).mkString("\n")
            val note =
              if (images.sizeIs > 1) {
                val extra = s"(${images.size} images returned; only the first is shown.)"
                if (text.isEmpty) extra else s"$text\n$extra"
              } else text
            ImageToolOutput(
              url  = context.sigil.storageUrl(stored),
              alt  = s"MCP image resource ${input.uri}",
              text = Option(note).filter(_.nonEmpty)
            )
          }
    }
  }

  private def contentText(c: Json): Option[String] =
    if (imageRef(c).isDefined) None
    else c.get("text").map(_.asString).orElse(Some(JsonFormatter.Compact(c)))

  /** `(base64Blob, mimeType)` for a resource content carrying a binary
    * `blob` whose `mimeType` is `image/…`. */
  private def imageRef(c: Json): Option[(String, String)] = {
    val mime = c.get("mimeType").map(_.asString).getOrElse("")
    if (mime.startsWith("image/")) c.get("blob").map(_.asString).map(_ -> mime) else None
  }
}
