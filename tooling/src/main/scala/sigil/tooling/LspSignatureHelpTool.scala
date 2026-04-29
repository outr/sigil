package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{MarkupContent, ParameterInformation, SignatureHelp, SignatureInformation}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class LspSignatureHelpInput(languageId: String,
                                 filePath: String,
                                 line: Int,
                                 character: Int) extends ToolInput derives RW

/**
 * Function-call signature help at a position — overload list, the
 * active overload, the active parameter. Equivalent to the popover
 * an editor shows when you've typed `foo(` and the cursor is between
 * the parens.
 *
 * The agent uses this to ground argument names + types when calling
 * a method whose signature isn't obvious from context.
 */
final class LspSignatureHelpTool(val manager: LspManager) extends TypedTool[LspSignatureHelpInput](
  name = ToolName("lsp_signature_help"),
  description =
    """Get function-call signature help at a position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at the call-site cursor (typically
      |inside the parens of a function call).
      |Returns each overload's signature plus the active overload / parameter index.""".stripMargin,
  examples = List(
    ToolExample(
      "scala signature help inside a method call",
      LspSignatureHelpInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 7)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspSignatureHelpInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.signatureHelp(uri, input.line, input.character).map(render)
    }

  private def render(help: Option[SignatureHelp]): String = help match {
    case None => "No signature help."
    case Some(h) =>
      val sigs = Option(h.getSignatures).map(_.asScala.toList).getOrElse(Nil)
      if (sigs.isEmpty) "No signature help."
      else {
        val active = Option(h.getActiveSignature).map(_.toInt).getOrElse(0)
        val activeParam = Option(h.getActiveParameter).map(_.toInt).getOrElse(-1)
        sigs.zipWithIndex.map { case (sig, idx) =>
          val marker = if (idx == active) "→" else " "
          s"$marker ${renderSignature(sig, if (idx == active) activeParam else -1)}"
        }.mkString("\n")
      }
  }

  private def renderSignature(sig: SignatureInformation, activeParam: Int): String = {
    val label = sig.getLabel
    val params = Option(sig.getParameters).map(_.asScala.toList).getOrElse(Nil)
    val paramNote =
      if (params.isEmpty || activeParam < 0 || activeParam >= params.size) ""
      else s"  (active param: ${params(activeParam).getLabel.getLeft})"
    val doc = Option(sig.getDocumentation) match {
      case Some(d) if d.isLeft => s"\n    ${d.getLeft}"
      case Some(d) =>
        val mc: MarkupContent = d.getRight
        if (mc != null && mc.getValue != null) s"\n    ${mc.getValue}" else ""
      case _ => ""
    }
    s"$label$paramNote$doc"
  }
}
