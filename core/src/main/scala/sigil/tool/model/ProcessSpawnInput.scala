package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `process_spawn` — fork a subprocess and detach. Returns
 * a handle for [[sigil.tool.process.ProcessRegistry]] follow-ups.
 * `command` runs through `bash -c`. Optional `workingDir` overrides
 * the conversation workspace; `env` augments (does not replace)
 * the parent environment; `stdin` is piped to the child once and
 * the stream is closed (so the child sees EOF).
 */
case class ProcessSpawnInput(command: String,
                             workingDir: Option[String] = None,
                             env: Option[Map[String, String]] = None,
                             stdin: Option[String] = None)
  extends ToolInput derives RW
