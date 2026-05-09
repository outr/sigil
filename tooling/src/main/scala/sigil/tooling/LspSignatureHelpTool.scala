package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{MarkupContent, SignatureHelp, SignatureInformation}
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{LspSignature, LspSignatureHelpResult, LspSignatureParam}

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
final class LspSignatureHelpTool(val manager: LspManager) extends TypedOutputTool[LspSignatureHelpInput, LspSignatureHelpResult](
  name = ToolName("lsp_signature_help"),
  description =
    """Get function-call signature help at a position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at the call-site cursor (typically
      |inside the parens of a function call).
      |Returns `{signatures: [{label, documentation, parameters}], activeSignature, activeParameter}`.
      |`activeParameter` is `-1` when no parameter is active or signatures is empty.""".stripMargin,
  keywords = Set("lsp", "signature", "parameters", "args", "arguments", "what does take", "function signature"),
  examples = List(
    ToolExample(
      "scala signature help inside a method call",
      LspSignatureHelpInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 7)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspSignatureHelpInput, context: TurnContext): Task[LspSignatureHelpResult] =
    withOpenDocumentTyped[LspSignatureHelpResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.signatureHelp(uri, input.line, input.character).map(toResult)
    }

  private def toResult(help: Option[SignatureHelp]): LspSignatureHelpResult = help match {
    case None => LspSignatureHelpResult(Nil, activeSignature = 0, activeParameter = -1)
    case Some(h) =>
      val sigs = Option(h.getSignatures).map(_.asScala.toList).getOrElse(Nil)
      LspSignatureHelpResult(
        signatures      = sigs.map(toSignature),
        activeSignature = Option(h.getActiveSignature).map(_.toInt).getOrElse(0),
        activeParameter = Option(h.getActiveParameter).map(_.toInt).getOrElse(-1)
      )
  }

  private def toSignature(sig: SignatureInformation): LspSignature =
    LspSignature(
      label = sig.getLabel,
      documentation = Option(sig.getDocumentation).flatMap { d =>
        if (d.isLeft) Option(d.getLeft)
        else {
          val mc: MarkupContent = d.getRight
          if (mc != null) Option(mc.getValue) else None
        }
      },
      parameters = Option(sig.getParameters).map(_.asScala.toList.map { p =>
        val lbl = p.getLabel
        val asString = if (lbl.isLeft) lbl.getLeft else {
          // Right side is a tuple of int offsets into the signature label;
          // round-trip the substring rather than the offsets.
          val offs = lbl.getRight
          if (offs == null) "" else sig.getLabel.substring(offs.getFirst.toInt, offs.getSecond.toInt)
        }
        LspSignatureParam(label = asString)
      }).getOrElse(Nil)
    )
}
