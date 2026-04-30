package sigil.metals

import fabric.io.JsonParser

import java.nio.file.{Files, Path}

/**
 * Parses `.metals/mcp.json` — Metals' rendezvous file for the MCP
 * server it spawns. The shape is one of:
 *
 * {{{
 *   { "port": 12345 }                           // localhost binding (current convention)
 *   { "url":  "http://localhost:12345/mcp" }    // explicit URL fallback
 * }}}
 *
 * Returns `None` when the file doesn't exist or its contents don't
 * resolve to either shape — the watcher then keeps polling until
 * Metals finishes startup (typically a couple of seconds).
 */
object MetalsRendezvous {

  /** Resolved MCP endpoint Metals exposes — either an explicit URL or
    * a localhost port the manager renders into the standard
    * `http://127.0.0.1:<port>/mcp` form. */
  final case class Endpoint(url: String)

  /** Read + parse `.metals/mcp.json` under `workspace`. Returns
    * `None` for any of: file missing, malformed JSON, unrecognized
    * shape. Errors are silent here — the watcher's retry loop is
    * the right place to log a stuck startup. */
  def read(workspace: Path): Option[Endpoint] = {
    val file = workspace.resolve(".metals").resolve("mcp.json")
    if (!Files.exists(file)) None
    else {
      try {
        val raw = Files.readString(file)
        val json = JsonParser(raw)
        json.get("url").map(_.asString).map(Endpoint.apply).orElse {
          json.get("port").map(_.asInt).map(p => Endpoint(s"http://127.0.0.1:$p/mcp"))
        }
      } catch {
        case _: Throwable => None
      }
    }
  }
}
