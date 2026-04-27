package spec

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets

/**
 * Minimal embedded MCP server used by [[McpStdioIntegrationSpec]].
 * Speaks the 2024-11-05 stdio transport — newline-delimited JSON-RPC
 * 2.0 messages on stdin/stdout. Implements only enough of the
 * protocol to exercise the client end-to-end: `initialize`,
 * `tools/list`, `tools/call`, `resources/list`, `resources/read`,
 * `prompts/list`, `prompts/get`. Notifications are accepted and
 * ignored.
 *
 * Launched as a child process by `ProcessBuilder` from the spec; the
 * spec's `StdioMcpClient` connects to it via standard pipes.
 */
object EmbeddedMcpServerMain {

  def main(args: Array[String]): Unit = {
    val in  = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
    val out = new PrintStream(System.out, true, StandardCharsets.UTF_8)
    var line = in.readLine()
    while (line != null) {
      if (line.nonEmpty) {
        try handle(JsonParser(line), out) catch { case _: Throwable => () }
      }
      line = in.readLine()
    }
  }

  private def handle(msg: Json, out: PrintStream): Unit = {
    val idOpt  = msg.get("id").flatMap(j => if (j == Null) None else Some(j))
    val method = msg.get("method").map(_.asString).getOrElse("")
    idOpt match {
      case None => () // notification — accept and discard
      case Some(id) =>
        val result = method match {
          case "initialize" => obj(
            "protocolVersion" -> str("2024-11-05"),
            "capabilities"    -> obj(),
            "serverInfo"      -> obj("name" -> str("embedded"), "version" -> str("0"))
          )
          case "tools/list" => obj(
            "tools" -> Arr(Vector(obj(
              "name" -> str("echo"),
              "description" -> str("echo the supplied text"),
              "inputSchema" -> obj(
                "type" -> str("object"),
                "properties" -> obj("text" -> obj("type" -> str("string"))),
                "required" -> Arr(Vector(str("text")))
              )
            )))
          )
          case "tools/call" =>
            val args = msg.get("params").flatMap(_.get("arguments")).getOrElse(Obj.empty)
            val text = args.get("text").map(_.asString).getOrElse("")
            obj(
              "content" -> Arr(Vector(obj("type" -> str("text"), "text" -> str(s"echoed:$text")))),
              "isError" -> bool(false)
            )
          case "resources/list" => obj(
            "resources" -> Arr(Vector(obj(
              "uri" -> str("test://example"),
              "name" -> str("Example"),
              "description" -> str("a test resource")
            )))
          )
          case "resources/read" => obj(
            "contents" -> Arr(Vector(obj(
              "uri"      -> str(msg.get("params").flatMap(_.get("uri")).map(_.asString).getOrElse("")),
              "mimeType" -> str("text/plain"),
              "text"     -> str("hello from embedded mcp")
            )))
          )
          case "prompts/list" => obj(
            "prompts" -> Arr(Vector(obj(
              "name" -> str("greet"),
              "description" -> str("a friendly greeting"),
              "arguments" -> Arr(Vector(obj("name" -> str("name"), "required" -> bool(true))))
            )))
          )
          case "prompts/get" =>
            val name = msg.get("params").flatMap(_.get("arguments")).flatMap(_.get("name")).map(_.asString).getOrElse("world")
            obj(
              "description" -> str("greeting"),
              "messages" -> Arr(Vector(obj(
                "role" -> str("user"),
                "content" -> obj("type" -> str("text"), "text" -> str(s"Hello, $name!"))
              )))
            )
          case _ => obj()
        }
        out.println(JsonFormatter.Compact(obj(
          "jsonrpc" -> str("2.0"),
          "id"      -> id,
          "result"  -> result
        )))
        out.flush()
    }
  }
}
