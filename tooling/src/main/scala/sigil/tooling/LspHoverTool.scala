package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{Hover, MarkupContent}
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

import java.io.File
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

case class LspHoverInput(languageId: String,
                         filePath: String,
                         line: Int,
                         character: Int) extends ToolInput derives RW

/**
 * Returns the hover information at a position — type signature,
 * inferred type, doc comment. The agent's "what is this thing"
 * query, equivalent to mousing over a symbol in an IDE.
 *
 * Markdown-formatted output (most servers ship `MarkupContent`).
 * Servers that respond with the legacy `MarkedString` shape are
 * coalesced into the same plain-string output.
 */
final class LspHoverTool(manager: LspManager) extends TypedTool[LspHoverInput](
  name = ToolName("lsp_hover"),
  description =
    """Get type signature + documentation at a source position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the symbol.
      |Returns markdown-formatted hover content (type, inferred signature, doc comments).""".stripMargin,
  examples = List(
    ToolExample(
      "scala hover on a symbol",
      LspHoverInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 5)
    )
  )
) {
  override protected def executeTyped(input: LspHoverInput, context: TurnContext): Stream[Event] = {
    val task = manager.configFor(input.languageId).flatMap {
      case None =>
        Task.pure(reply(context, s"No LspServerConfig persisted for '${input.languageId}'.", isError = true))
      case Some(config) =>
        val root = manager.resolveRoot(input.filePath, config.rootMarkers)
        val uri = new File(input.filePath).toURI.toString
        manager.session(input.languageId, root).flatMap { session =>
          val text = scala.util.Try(Files.readString(Paths.get(input.filePath))).toOption.getOrElse("")
          for {
            _ <- session.didOpen(uri, input.languageId, text)
            hov <- session.hover(uri, input.line, input.character)
          } yield reply(context, render(hov), isError = false)
        }.handleError { e =>
          Task.pure(reply(context, s"LSP error: ${e.getMessage}", isError = true))
        }
    }
    Stream.force(task.map(Stream.emit))
  }

  private def render(hover: Option[Hover]): String = hover match {
    case None    => "No hover information."
    case Some(h) =>
      val contents = h.getContents
      if (contents == null) "No hover information."
      else if (contents.isLeft) {
        contents.getLeft.asScala.map { either =>
          if (either.isLeft) either.getLeft else either.getRight.getValue
        }.mkString("\n\n")
      } else {
        val mc: MarkupContent = contents.getRight
        if (mc == null || mc.getValue == null) "No hover information." else mc.getValue
      }
  }

  private def reply(context: TurnContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )
}
