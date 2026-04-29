package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

import java.io.File
import java.nio.file.{Files, Paths}

case class LspGotoDefinitionInput(languageId: String,
                                  filePath: String,
                                  line: Int,
                                  character: Int) extends ToolInput derives RW

/**
 * Locate where a symbol is defined. `line` and `character` are 0-based
 * (LSP convention) and identify a position inside an identifier in
 * the source file. The server returns one or more file URIs with
 * ranges — usually one for primary definitions, multiple for
 * overloaded methods or partial-class members.
 *
 * Higher-leverage than a `grep_definition` regex search because the
 * language server resolves through the actual symbol resolution
 * graph (imports, type aliases, generics) — finds the right Foo
 * when there are nine `Foo` in scope.
 */
final class LspGotoDefinitionTool(manager: LspManager) extends TypedTool[LspGotoDefinitionInput](
  name = ToolName("lsp_goto_definition"),
  description =
    """Find where a symbol is defined.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the identifier.
      |Returns one or more file:line:character locations.""".stripMargin,
  examples = List(
    ToolExample(
      "scala goto-def at line 42 col 12",
      LspGotoDefinitionInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 42, character = 12)
    )
  )
) {
  override protected def executeTyped(input: LspGotoDefinitionInput, context: TurnContext): Stream[Event] = {
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
            locations <- session.gotoDefinition(uri, input.line, input.character)
          } yield {
            if (locations.isEmpty) reply(context, "No definition found.", isError = false)
            else reply(context, render(locations), isError = false)
          }
        }.handleError { e =>
          Task.pure(reply(context, s"LSP error: ${e.getMessage}", isError = true))
        }
    }
    Stream.force(task.map(Stream.emit))
  }

  private def render(locations: List[org.eclipse.lsp4j.Location]): String =
    locations.map { l =>
      val r = l.getRange
      s"  ${l.getUri} ${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
    }.mkString("\n")

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
