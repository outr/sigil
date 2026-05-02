package sigil.script

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[ReadSourceTool]]. The agent supplies a fully-qualified
 * symbol; the tool returns the symbol's source code if a
 * `-sources.jar` is on the classpath (sbt typically downloads them
 * for IDE support), or `(source not available)` otherwise.
 *
 * This is the deepest level of introspection — used when signature
 * alone isn't enough to figure out semantics (e.g. understanding
 * what a builder method actually configures).
 */
case class ReadSourceInput(
  @description("Fully-qualified symbol whose source to read — class, object, or method (`spice.http.client.HttpClient.url`). Returns `(source not available)` when no `-sources.jar` ships the symbol's source.")
  fqn: String
) extends ToolInput derives RW
