package sigil.tool

import fabric.rw.*
import sigil.orchestrator.Directive

/**
 * The typed payload carried by a framework directive's synthetic
 * `ToolInvoke`. These invokes have no `Tool` behind them — the input
 * exists so the directive's structure persists alongside the prose the
 * model reads, instead of the invoke rendering with empty args.
 */
case class DirectiveInput(directive: Directive) extends ToolInput derives RW
