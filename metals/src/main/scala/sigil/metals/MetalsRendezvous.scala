package sigil.metals

import fabric.io.JsonParser

import java.nio.file.{Files, Path}

/**
 * Parses `.metals/mcp.json` — Metals' rendezvous file for the MCP
 * server it spawns. Three known shapes (Metals 1.6+ writes the
 * nested form; flat shapes are kept for fixture-driven tests + as
 * a fallback if Metals' on-disk format ever changes back):
 *
 * {{{
 *   // Metals 1.6+ NoClient layout (real binary)
 *   { "servers": { "<projectName>-metals": { "url": "http://localhost:12345/mcp" } } }
 *
 *   // Flat URL
 *   { "url":  "http://localhost:12345/mcp" }
 *
 *   // Legacy port-only
 *   { "port": 12345 }
 * }}}
 *
 * Returns `None` when the file doesn't exist or its contents don't
 * resolve to any recognized shape — the watcher then keeps polling
 * until Metals finishes startup.
 */
object MetalsRendezvous {

  /**
   * Resolved MCP endpoint Metals exposes — either an explicit URL or
   * a localhost port the manager renders into the standard
   * `http://127.0.0.1:<port>/mcp` form.
   */
  final case class Endpoint(url: String)

  /**
   * Read + parse `.metals/mcp.json` under `workspace`. Returns
   * `None` for any of: file missing, malformed JSON, unrecognized
   * shape. Errors are silent here — the watcher's retry loop is
   * the right place to log a stuck startup.
   */
  def read(workspace: Path): Option[Endpoint] = {
    val file = workspace.resolve(".metals").resolve("mcp.json")
    if (!Files.exists(file)) None
    else {
      try {
        val raw = Files.readString(file)
        val json = JsonParser(raw)
        // Metals 1.6+ shape: `{servers: {<name>: {url: ...}}}`. Pick
        // the first server entry's url — there's typically one.
        val nested = json.get("servers").flatMap { servers =>
          servers.asObj.value.values.flatMap(_.get("url").map(_.asString)).headOption
        }.map(Endpoint.apply)
        nested
          .orElse(json.get("url").map(_.asString).map(Endpoint.apply))
          .orElse(json.get("port").map(_.asInt).map(p => Endpoint(s"http://127.0.0.1:$p/mcp")))
      } catch {
        case _: Throwable => None
      }
    }
  }
}
