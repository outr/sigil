package sigil.tooling

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

import java.io.File
import java.nio.file.{Files, Paths}

/**
 * Shared plumbing for the agent-facing LSP tools. Every tool follows
 * the same shape: look up a config by language id, resolve a project
 * root, get a session, open the target document so the server has it
 * indexed, run a single RPC, format the response. This trait
 * collapses that into [[withSession]] and [[withOpenDocument]] so
 * the per-tool body is just "call session.X, format the result."
 */
trait LspToolSupport {
  protected def manager: LspManager

  /** Run `body` against a session for `languageId` rooted appropriately
    * for `filePath`. Wraps every error path (no config / spawn failure
    * / RPC error) into a single Stream emission carrying an explanatory
    * Message — agents always receive a structured tool-result, never
    * an exception. */
  protected def withSession(languageId: String,
                            filePath: String,
                            context: TurnContext)
                           (body: (LspSession, String, String) => Task[String]): Stream[Event] = {
    val task = manager.configFor(languageId).flatMap {
      case None =>
        Task.pure(reply(context, s"No LspServerConfig persisted for '$languageId'.", isError = true))
      case Some(config) =>
        val root = manager.resolveRoot(filePath, config.rootMarkers)
        val uri = new File(filePath).toURI.toString
        manager.session(languageId, root).flatMap { session =>
          body(session, uri, root).map(text => reply(context, text, isError = false))
        }.handleError { e =>
          Task.pure(reply(context, s"LSP error: ${e.getMessage}", isError = true))
        }
    }
    Stream.force(task.map(Stream.emit))
  }

  /** Convenience around [[withSession]] that also calls `didOpen` on
    * the target file before running `body`, reading the file's bytes
    * fresh from disk. Most navigation / completion / hover tools
    * want this. Returns "" body if the file doesn't exist or can't
    * be read — the LSP call itself will fail in a meaningful way. */
  protected def withOpenDocument(languageId: String,
                                 filePath: String,
                                 context: TurnContext)
                                (body: (LspSession, String) => Task[String]): Stream[Event] =
    withSession(languageId, filePath, context) { (session, uri, _) =>
      val text = scala.util.Try(Files.readString(Paths.get(filePath))).toOption.getOrElse("")
      session.didOpen(uri, languageId, text).flatMap(_ => body(session, uri))
    }

  protected def reply(context: TurnContext, text: String, isError: Boolean): Event =
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
